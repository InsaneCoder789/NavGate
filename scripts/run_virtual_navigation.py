"""Build a frozen backend snapshot and exercise two synthetic locations through emulator GPS.

The virtual app has its own application ID; production/frontend working files and data are untouched.
Requires an already-running Android API 34+ emulator and an installed JDK/SDK.
"""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import subprocess
import tarfile
import threading
import time

PACKAGE = 'com.rohanc.navgate.virtualtest'
TEST = 'com.rohanc.navgate.data.local.VirtualGpsJourneyTest'


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--run-dir', type=Path, required=True)
    parser.add_argument('--jdk', type=Path, required=True)
    parser.add_argument('--sdk', type=Path, required=True)
    args = parser.parse_args()
    if not args.serial.startswith('emulator-'):
        raise SystemExit('Only an Android emulator may receive synthetic GPS fixes')
    root = Path(__file__).resolve().parents[1]
    run = args.run_dir.resolve()
    if not run.is_relative_to(root / '.qa' / 'runs'):
        raise SystemExit('Evidence and snapshot must stay under this project .qa/runs')
    run.mkdir(parents=True, exist_ok=True)
    workspace = run / 'backend-snapshot'
    workspace.mkdir(exist_ok=False)
    revision = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=root, text=True).strip()
    archive = subprocess.Popen(['git', 'archive', revision], cwd=root, stdout=subprocess.PIPE)
    with tarfile.open(fileobj=archive.stdout, mode='r|') as bundle:
        bundle.extractall(workspace, filter='data')
    if archive.wait() != 0:
        raise SystemExit('Cannot create frozen backend snapshot')
    test_path = Path('app/src/androidTest/java/com/rohanc/navgate/data/local/VirtualGpsJourneyTest.kt')
    (workspace / test_path).write_bytes((root / test_path).read_bytes())
    gradle = workspace / 'app/build.gradle.kts'
    source = gradle.read_text()
    assert 'applicationId = "com.rohanc.navgate"' in source
    gradle.write_text(source.replace('applicationId = "com.rohanc.navgate"', f'applicationId = "{PACKAGE}"'))
    env = dict(os.environ, JAVA_HOME=str(args.jdk), ANDROID_HOME=str(args.sdk), ANDROID_SERIAL=args.serial)
    adb = [str(args.sdk / 'platform-tools/adb'), '-s', args.serial]
    def device(*command: str, timeout: int = 30) -> str:
        return subprocess.check_output(adb + list(command), text=True, stderr=subprocess.STDOUT, timeout=timeout)
    device('wait-for-device', timeout=90)
    deadline = time.monotonic() + 90
    while device('shell', 'getprop', 'sys.boot_completed').strip() != '1':
        if time.monotonic() > deadline:
            raise RuntimeError('Emulator did not finish booting')
        time.sleep(1)
    subprocess.run([str(workspace / 'gradlew'), ':navigation-core:test', ':app:testDebugUnitTest',
                    ':app:assembleDebug', ':app:assembleDebugAndroidTest', '--console=plain'],
                   cwd=workspace, env=env, check=True)
    app_apk = workspace / 'app/build/outputs/apk/debug/app-debug.apk'
    test_apk = workspace / 'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk'
    print(device('install', '-r', '-g', str(app_apk)), flush=True)
    print(device('install', '-r', '-g', str(test_apk)), flush=True)
    print(device('shell', 'cmd', 'location', 'set-location-enabled', 'true'), flush=True)
    walking, resumed, stop = threading.Event(), threading.Event(), threading.Event()
    injection_errors: list[str] = []
    trace: list[dict] = []
    def inject(longitude: float) -> None:
        result = device('emu', 'geo', 'fix', f'{longitude:.8f}', '20.00000000')
        if 'OK' not in result:
            raise RuntimeError(result)
        trace.append({'monotonicSeconds': time.monotonic(), 'latitude': 20.0, 'longitude': longitude})
    def gps_driver() -> None:
        try:
            start_deadline = time.monotonic() + 25
            while not walking.is_set() and not stop.is_set():
                inject(85.0)
                if time.monotonic() > start_deadline:
                    raise RuntimeError('App did not accept start at virtual location A')
                walking.wait(1)
            for step in range(1, 7):
                if stop.is_set(): return
                inject(85.0 + step * 0.00005)
                stop.wait(2)
            if not resumed.wait(30):
                raise RuntimeError('No recovery marker after controlled GPS delivery outage')
            for step in range(7, 20):
                if stop.is_set(): return
                inject(85.0 + step * 0.00005)
                stop.wait(2)
            # Distinct near-end fixes overcome the production 1m minimum displacement rule.
            for longitude in (85.00098, 85.001):
                if stop.is_set(): return
                inject(longitude)
                stop.wait(2)
        except Exception as error:
            injection_errors.append(str(error))
    command = adb + ['shell', 'am', 'instrument', '-w', '-r', '-e', 'class', TEST,
                     '-e', 'virtualGpsJourney', 'true', PACKAGE + '.test/androidx.test.runner.AndroidJUnitRunner']
    (run / 'virtual-configuration.json').write_text(json.dumps({
        'backendRevision': revision, 'applicationId': PACKAGE, 'serial': args.serial,
        'origin': {'latitude': 20.0, 'longitude': 85.0},
        'destination': {'latitude': 20.0, 'longitude': 85.001},
        'graph': 'Synthetic test only; no KIIT routing data or frontend edits are promoted',
        'testFile': str(test_path), 'command': command,
    }, indent=2) + '\n')
    process = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    worker = None
    timed_out = threading.Event()
    def timeout() -> None:
        timed_out.set(); stop.set(); process.kill()
    timer = threading.Timer(160, timeout)
    timer.start()
    lines = []
    try:
        with (run / 'virtual-instrumentation.txt').open('w') as log:
            for line in process.stdout:
                lines.append(line); log.write(line); log.flush(); print(line.rstrip(), flush=True)
                if 'NAVGATE_VIRTUAL_READY' in line and worker is None:
                    worker = threading.Thread(target=gps_driver, daemon=True); worker.start()
                if 'NAVGATE_VIRTUAL_WALKING' in line: walking.set()
                if 'NAVGATE_VIRTUAL_RESUME' in line: resumed.set()
        process.wait()
    finally:
        timer.cancel(); stop.set(); resumed.set()
        if worker: worker.join(timeout=5)
        (run / 'gps-trace.json').write_text(json.dumps(trace, indent=2) + '\n')
        if timed_out.is_set():
            device('shell', 'am', 'force-stop', PACKAGE)
    text = ''.join(lines)
    passed = (process.returncode == 0 and 'OK (1 test)' in text and 'NAVGATE_VIRTUAL_PASSED' in text
              and not injection_errors and not timed_out.is_set())
    summary = {'passed': passed, 'backendRevision': revision, 'gpsFixesInjected': len(trace),
               'injectionErrors': injection_errors, 'timedOut': timed_out.is_set(),
               'scope': 'Emulator GPS -> test-owned Android listener -> production Kotlin backend -> isolated Room DB; no Compose/UI claim'}
    (run / 'virtual-result.json').write_text(json.dumps(summary, indent=2) + '\n')
    print(json.dumps(summary, indent=2), flush=True)
    if not passed:
        raise SystemExit(1)


if __name__ == '__main__':
    main()
