#!/usr/bin/env bash
# ============================================================================
# Manual overlay lifecycle test — hermes-quick-prompt
#
# For devices/Android versions where the instrumented suite cannot run
# (e.g. no UiAutomation access, no test APK install, ADB-only human-in-loop,
# OEM builds that restrict `am start -A ASSIST`), this script drives the SAME
# four lifecycle checks by hand with adb and reports PASS/FAIL per step.
# Requires only: adb + a device with the app installed + overlay permission.
#
#   adb version: any (works down to API 26 == minSdk of the app)
#   usage:  ADB=/path/to/adb PACKAGE=dev.hermesprompt.app   \
#           YOUR_ASSIST_ACTION=long-press-power             \
#           bash scripts/manual-overlay-test.sh
#   or simply:  ADB=adb bash scripts/manual-overlay-test.sh
#
# Flow per check:
#   a) another app foreground → summon → previous app must stay topResumed
#   b) show + dismiss → no new task, no Recents card ever created
#   c) type a question → answer renders inside the overlay (real server)
#   d) tap outside → overlay dismissed → back to the previous app
# ============================================================================
set -u

ADB="${ADB:-adb}"
PACKAGE="${PACKAGE:-dev.hermesprompt.app}"
PREV_APP="${PREV_APP:-org.lineageos.jelly}"
PREV_ACTIVITY="${PREV_ACTIVITY:-org.lineageos.jelly/.MainActivity}"
SHOT_DIR="/data/local/tmp/overlay-manual"
PASS=0; FAIL=0

log()  { printf '\n\033[1m%s\033[0m\n' "$*"; }
ok()   { printf '  \033[32mPASS\033[0m  %s\n' "$*"; PASS=$((PASS+1)); }
bad()  { printf '  \033[31mFAIL\033[0m  %s\n' "$*"; FAIL=$((FAIL+1)); }

"$ADB" devices | grep -q 'device$' || { echo "no device — connect one"; exit 1; }
"$ADB" shell mkdir -p "$SHOT_DIR"

top_resumed() { "$ADB" shell "dumpsys activity activities" | grep topResumedActivity | sed 's/.*=//'; }

overlay_up()   { "$ADB" shell "dumpsys window windows" | grep -q "$PACKAGE"; }
hermes_activity_records() {
  "$ADB" shell "dumpsys activity activities" | grep -c "ActivityRecord.*$PACKAGE" || true
}
hermes_tasks() {
  "$ADB" shell "dumpsys activity tasks" | grep -c "$PACKAGE" || true
}

# ── preflight ───────────────────────────────────────────────────────────────
log "PREFLIGHT"
"$ADB" shell cmd appops set "$PACKAGE" SYSTEM_ALERT_WINDOW allow
if "$ADB" shell cmd appops get "$PACKAGE" SYSTEM_ALERT_WINDOW | grep -q allow; then
  ok "SYSTEM_ALERT_WINDOW granted"
else
  bad "SYSTEM_ALERT_WINDOW — open Settings > Apps > $PACKAGE > Display over other apps and enable, then rerun"
  exit 1
fi
"$ADB" shell am start -n "$PREV_ACTIVITY" >/dev/null 2>&1
sleep 1

# ── (a) summon over another app ─────────────────────────────────────────────
log "A) SUMMON OVER ANOTHER APP"
before="$(top_resumed)"
log "   previous app foreground: $before"
log "   > SUMMON NOW: long-press power (or assistant gesture), or:"
log "   > $ADB shell am start -a android.intent.action.ASSIST -n $PACKAGE/.ui.MainActivity"
read -r -p "   press ENTER after the Hermes overlay is visible... "
if overlay_up; then ok "overlay window present"; else bad "no overlay window"; fi
"$ADB" shell screencap -p "$SHOT_DIR/a-overlay.png" >/dev/null
after="$(top_resumed)"
if [ "$before" = "$after" ]; then
  ok "previous app stayed topResumed ($after) — its Activity was NOT destroyed"
else
  bad "topResumed changed $before -> $after — the app below was backgrounded"
fi
if [ "$(hermes_activity_records)" -eq 0 ]; then
  ok "no Hermes ActivityRecord (summon activity finished)"
else
  bad "Hermes ActivityRecord exists — summon left an activity behind"
fi

# ── (b) show + dismiss without a task ───────────────────────────────────────
log "B) SHOW + DISMISS WITHOUT A TASK"
if overlay_up; then ok "overlay still shown"; fi
if [ "$(hermes_tasks)" -eq 0 ]; then
  ok "no Hermes task while overlay is shown"
else
  bad "a Hermes task exists — overlay must be a bare window, not a task"
fi
log "   > DISMISS: tap the close (X) on the overlay card, or the scrim"
read -r -p "   press ENTER after the overlay is GONE... "
if overlay_up; then bad "overlay still present after dismiss"; else ok "overlay dismissed"; fi
if [ "$(hermes_tasks)" -eq 0 ]; then
  ok "no Hermes task after dismiss"
else
  bad "a Hermes task exists after dismiss"
fi
"$ADB" shell screencap -p "$SHOT_DIR/b-dismissed.png" >/dev/null

# ── (c) question → answer inside the overlay ─────────────────────────────────
log "C) QUESTION → ANSWER INSIDE THE OVERLAY"
log "   (needs a reachable Hermes server configured in the app's Settings UI)"
log "   > SUMMON, TYPE a question, SEND"
read -r -p "   press ENTER after the answer finished streaming INSIDE the overlay... "
if overlay_up; then ok "overlay still shown with the answer"; else bad "overlay gone before answer"; fi
"$ADB" shell screencap -p "$SHOT_DIR/c-answer.png" >/dev/null

# ── (d) tap outside dismisses → back to previous app ─────────────────────────
log "D) TAP OUTSIDE → DISMISS + RETURN"
log "   > TAP the scrim area (outside the card, e.g. top half of the screen)"
read -r -p "   press ENTER after tapping outside... "
if overlay_up; then bad "overlay did not dismiss on tap-outside"; else ok "tap-outside dismissed the overlay"; fi
returned="$(top_resumed)"
if [ "$returned" = "$before" ]; then
  ok "previous app resumed ($returned)"
else
  bad "previous app is NOT foreground (now: $returned)"
fi
"$ADB" shell screencap -p "$SHOT_DIR/d-returned.png" >/dev/null

log "SHOTS in $SHOT_DIR on device:"
"$ADB" shell ls -l "$SHOT_DIR"
log "RESULT: $PASS passed, $FAIL failed"
[ "$FAIL" -eq 0 ] || exit 1