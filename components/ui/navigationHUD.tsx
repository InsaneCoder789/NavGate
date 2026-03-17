import MaterialIcons from "@expo/vector-icons/MaterialIcons";
import React from "react";
import { StyleSheet, Text, TouchableOpacity, View } from "react-native";

function getTurnIcon(instruction: string) {
  const text = instruction?.toLowerCase() || "";
  if (text.includes("left")) return "turn-left";
  if (text.includes("right")) return "turn-right";
  if (text.includes("roundabout")) return "roundabout-left";
  if (text.includes("arrive")) return "flag";
  return "navigation";
}

export type HUDProps = {
  instruction: string;
  distance: number;
  eta?: number;
  mode?: "car" | "bike" | "walk";
  onModeChange?: (mode: "car" | "bike" | "walk") => void;
  bearing?: number;
  onStart?: () => void;
  isNavigating?: boolean;
};

function formatDistance(m: number) {
  if (m > 1000) return (m / 1000).toFixed(1) + " km";
  return Math.max(0, Math.round(m)) + " m";
}

function formatETA(sec?: number) {
  if (!sec) return "";
  const min = Math.round(sec / 60);
  if (min < 60) return `${min} min`;
  const h = Math.floor(min / 60);
  const m = min % 60;
  return `${h}h ${m}m`;
}

export default function NavigationHUD({
  instruction,
  distance,
  eta,
  mode = "car",
  bearing = 0,
  onStart,
  onModeChange,
  isNavigating = false,
}: HUDProps) {
  const iconName = getTurnIcon(instruction);
  const isDirectional = iconName === "navigation";

  return (
    <View style={styles.container}>
      <View style={styles.card}>
        {/* Instruction Row */}
        <View style={styles.row}>
          <MaterialIcons
            name={iconName as any}
            size={28}
            color="#00E5FF"
            style={{
              marginRight: 10,
              ...(isDirectional
                ? { transform: [{ rotate: `${bearing}deg` }] }
                : {}),
            }}
          />
          <Text style={styles.instruction}>
            {instruction || "Start navigation"}
          </Text>
        </View>

        {/* Distance + ETA */}
        <View style={styles.rowBetween}>
          <Text style={styles.distance}>{formatDistance(distance)}</Text>
          {eta !== undefined && <Text style={styles.eta}>{formatETA(eta)}</Text>}
        </View>

        {/* Start Button */}
        {!isNavigating && (
          <TouchableOpacity
            style={styles.startBtn}
            onPress={() => onStart?.()}
          >
            <Text style={styles.startText}>Start Navigation</Text>
          </TouchableOpacity>
        )}

        {/* Mode Switch */}
        <View style={styles.modeRow}>
          <ModeChip active={mode === "car"} icon="directions-car" label="Car" onPress={() => onModeChange?.("car")} />
          <ModeChip active={mode === "bike"} icon="directions-bike" label="Bike" onPress={() => onModeChange?.("bike")} />
          <ModeChip active={mode === "walk"} icon="directions-walk" label="Walk" onPress={() => onModeChange?.("walk")} />
        </View>
      </View>
    </View>
  );
}

function ModeChip({ active, icon, label, onPress }: any) {
  return (
    <TouchableOpacity onPress={onPress} style={[styles.chip, active && styles.chipActive]}>
      <MaterialIcons name={icon} size={16} color={active ? "#000" : "#aaa"} />
      <Text style={[styles.chipText, active && { color: "#000" }]}>
        {label}
      </Text>
    </TouchableOpacity>
  );
}

const styles = StyleSheet.create({
  container: {
    position: "absolute",
    bottom: 24,
    width: "100%",
    alignItems: "center",
  },
  card: {
    width: "92%",
    backgroundColor: "#000",
    padding: 16,
    borderRadius: 16,
  },
  row: { flexDirection: "row", alignItems: "center" },
  rowBetween: { flexDirection: "row", justifyContent: "space-between", marginTop: 8 },
  instruction: { color: "#fff", fontSize: 18, flex: 1 },
  distance: { color: "#00E5FF", fontSize: 18 },
  eta: { color: "#aaa" },
  startBtn: { marginTop: 12, backgroundColor: "#00E5FF", padding: 10, borderRadius: 10 },
  startText: { color: "#000", textAlign: "center" },
  modeRow: { flexDirection: "row", marginTop: 10 },
  chip: { flexDirection: "row", padding: 8, marginRight: 8, backgroundColor: "#222", borderRadius: 20 },
  chipActive: { backgroundColor: "#00E5FF" },
  chipText: { marginLeft: 5, color: "#aaa" },
});