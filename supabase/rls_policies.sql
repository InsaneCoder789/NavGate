-- NavGate Supabase RLS starter
-- Review before applying in production.

ALTER TABLE public.profiles ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.saved_places ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.recent_places ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.beta_bug_reports ENABLE ROW LEVEL SECURITY;

CREATE POLICY "profiles_select_own" ON public.profiles
FOR SELECT USING (auth.uid() = id);

CREATE POLICY "profiles_update_own" ON public.profiles
FOR UPDATE USING (auth.uid() = id);

CREATE POLICY "profiles_insert_own" ON public.profiles
FOR INSERT WITH CHECK (auth.uid() = id);

CREATE POLICY "saved_places_own_all" ON public.saved_places
FOR ALL USING (auth.uid() = profile_id)
WITH CHECK (auth.uid() = profile_id);

CREATE POLICY "recent_places_own_all" ON public.recent_places
FOR ALL USING (auth.uid() = profile_id)
WITH CHECK (auth.uid() = profile_id);

CREATE POLICY "beta_bug_reports_insert_own" ON public.beta_bug_reports
FOR INSERT WITH CHECK (auth.uid() = profile_id OR profile_id IS NULL);

CREATE POLICY "beta_bug_reports_select_own" ON public.beta_bug_reports
FOR SELECT USING (auth.uid() = profile_id);
