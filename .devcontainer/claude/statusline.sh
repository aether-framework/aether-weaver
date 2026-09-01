#!/usr/bin/env bash
# Multi-line status line for Claude Code.
# Reads the session JSON on stdin, prints three coloured lines.

# Builtin read, so the no-jq fallback needs no external binary at all.
input=""
IFS= read -r -d '' input

# jq is the only hard dependency. Without it, degrade to one quiet line.
if ! command -v jq >/dev/null 2>&1; then
  printf '%s\n' "${PWD##*/}"
  exit 0
fi

E=$'\033'
R="${E}[0m"
SEP=$'\001'

# ---------------------------------------------------------------- extraction
# One record, SEP-separated, every field defensively stringified.
IFS="$SEP" read -r \
  ctx_pct model tok_used tok_max fast thinking \
  fh_pct fh_reset sd_pct sd_reset \
  cost added removed dir effort session \
  < <(printf '%s' "$input" | jq -r '
    def s: if . == null then "" else tostring end;
    def pct: (tonumber? // null) as $n
             | if $n == null then "" else ($n | floor | tostring) end;
    def num: (tonumber? // null) as $n
             | if $n == null then "" else ($n | tostring) end;
    [ (.context_window.used_percentage        | pct)
    , (.model.display_name                    | s)
    , (.context_window.total_input_tokens     | num)
    , (.context_window.context_window_size    | num)
    , (.fast_mode                             | s)
    , (.thinking.enabled                      | s)
    , (.rate_limits.five_hour.used_percentage | pct)
    , (.rate_limits.five_hour.resets_at       | s)
    , (.rate_limits.seven_day.used_percentage | pct)
    , (.rate_limits.seven_day.resets_at       | s)
    , (.cost.total_cost_usd                   | num)
    , (.cost.total_lines_added                | num)
    , (.cost.total_lines_removed              | num)
    , (.workspace.current_dir                 | s)
    , (.effort.level                          | s)
    , (.session_name                          | s)
    ] | join("\u0001")' 2>/dev/null)

# ------------------------------------------------------------------- helpers

# Traffic light for a percentage: <50 green, 50-74 yellow, 75-89 orange,
# >=90 red and bold.
light() {
  local p="$1"
  if [ -z "$p" ]; then printf '%s' "${E}[38;2;120;120;120m"; return; fi
  if   [ "$p" -lt 50 ]; then printf '%s' "${E}[38;2;60;200;90m"
  elif [ "$p" -lt 75 ]; then printf '%s' "${E}[38;2;235;200;0m"
  elif [ "$p" -lt 90 ]; then printf '%s' "${E}[38;2;255;145;0m"
  else                       printf '%s' "${E}[1;38;2;255;70;70m"
  fi
}

# Gradient colour at position t (0..1000 thousandths of the bar):
# green -> yellow over the first half, yellow -> red over the second.
grad() {
  local t=$1 u r g b
  if [ "$t" -lt 500 ]; then
    u=$(( t * 2 ))
    r=$((   0 + ( 235 -   0 ) * u / 1000 ))
    g=$(( 200 + ( 200 - 200 ) * u / 1000 ))
    b=$((  80 + (   0 -  80 ) * u / 1000 ))
  else
    u=$(( ( t - 500 ) * 2 ))
    r=$(( 235 + ( 215 - 235 ) * u / 1000 ))
    g=$(( 200 + (  45 - 200 ) * u / 1000 ))
    b=$((   0 + (  45 -   0 ) * u / 1000 ))
  fi
  printf '%d;%d;%d' "$r" "$g" "$b"
}

# The host clock is UTC, so reset stamps are rendered in this zone instead.
# A named zone rather than a fixed offset, so CET and CEST both come out right.
LOCAL_TZ="${AW_STATUSLINE_TZ:-Europe/Berlin}"
[ -e "/usr/share/zoneinfo/$LOCAL_TZ" ] || LOCAL_TZ="$TZ"

# HH:MM in LOCAL_TZ from either an ISO-8601 stamp or epoch seconds/millis.
fmt_time() {
  local v="$1"
  [ -z "$v" ] && return 0
  if [[ "$v" =~ ^[0-9]+$ ]]; then
    [ "${#v}" -ge 13 ] && v=$(( v / 1000 ))
    TZ="$LOCAL_TZ" date -d "@$v" '+%H:%M' 2>/dev/null
  else
    TZ="$LOCAL_TZ" date -d "$v" '+%H:%M' 2>/dev/null
  fi
}

# Whole thousands, for token counts.
k() { [ -z "$1" ] && return 0; printf '%dk' "$(( ${1%%.*} / 1000 ))"; }

# --------------------------------------------------------------- line 1: bar
WIDTH=24
pct=${ctx_pct:-0}
[ "$pct" -gt 100 ] 2>/dev/null && pct=100
[ "$pct" -lt 0   ] 2>/dev/null && pct=0
filled=$(( pct * WIDTH / 100 ))

bar=""
for (( i = 0; i < WIDTH; i++ )); do
  if [ "$i" -lt "$filled" ]; then
    bar+="${E}[48;2;$(grad $(( i * 1000 / (WIDTH - 1) )))m "
  else
    bar+="${E}[48;2;58;58;62m "
  fi
done
bar+="$R"

line1=""
[ "$fast" = "true" ]     && line1+="\U0001F525 "
[ "$thinking" = "true" ] && line1+="\U0001F9E0 "
line1=$(printf '%b' "$line1")
line1+="$bar"

if [ -n "$ctx_pct" ]; then
  line1+=" $(light "$ctx_pct")${ctx_pct}%${R}"
else
  line1+=" ${E}[38;2;120;120;120m--%${R}"
fi

[ -n "$model" ] && line1+="  ${E}[38;2;150;170;255m${model}${R}"

if [ -n "$tok_used" ] && [ -n "$tok_max" ]; then
  line1+="  ${E}[38;2;140;140;145m$(k "$tok_used")/$(k "$tok_max")${R}"
elif [ -n "$tok_used" ]; then
  line1+="  ${E}[38;2;140;140;145m$(k "$tok_used")${R}"
fi

# ------------------------------------------------------ line 2: limits, cost
DIM="${E}[38;2;140;140;145m"
FAINT="${E}[38;2;110;110;115m"
GREY="${E}[38;2;120;120;120m"
PIPE="  ${E}[38;2;80;80;85m|${R}  "
DOT=$(printf '%b' '●')
CLOCK=$(printf '%b' '\U000023F0')

line2=""

if [ -n "$fh_pct" ]; then
  line2+="$(light "$fh_pct")${DOT}${R} ${DIM}5h${R} $(light "$fh_pct")${fh_pct}%${R}"
  t=$(fmt_time "$fh_reset")
  [ -n "$t" ] && line2+=" ${CLOCK} ${FAINT}${t}${R}"
else
  line2+="${GREY}${DOT} 5h --%${R}"
fi

line2+="$PIPE"

if [ -n "$sd_pct" ]; then
  line2+="$(light "$sd_pct")${DOT}${R} ${DIM}7d${R} $(light "$sd_pct")${sd_pct}%${R}"
  t=$(fmt_time "$sd_reset")
  [ -n "$t" ] && line2+=" ${CLOCK} ${FAINT}${t}${R}"
else
  line2+="${GREY}${DOT} 7d --%${R}"
fi

if [ -n "$cost" ]; then
  line2+="${PIPE}${E}[38;2;200;200;120m\$$(printf '%.2f' "$cost")${R}"
fi

if [ -n "$added" ] || [ -n "$removed" ]; then
  line2+="${PIPE}${E}[38;2;60;200;90m+${added:-0}${R} ${E}[38;2;255;70;70m-${removed:-0}${R}"
fi

# ------------------------------------------------- line 3: place and session
BRANCH_ICON=$(printf '%b' '\U0001F33F')
line3=""
[ -n "$dir" ] && line3+="${E}[38;2;110;190;220m${dir##*/}${R}"

if [ -n "$dir" ] && [ -d "$dir" ]; then
  branch=$(git -C "$dir" rev-parse --abbrev-ref HEAD 2>/dev/null)
  if [ -n "$branch" ]; then
    changed=$(git -C "$dir" status --porcelain 2>/dev/null | grep -c '')
    line3+="${PIPE}${E}[38;2;200;150;255m${BRANCH_ICON} ${branch}${R}"
    if [ "${changed:-0}" -gt 0 ]; then
      line3+=" ${E}[38;2;235;200;0m${changed} changed${R}"
    fi
  fi
fi

[ -n "$effort" ]  && line3+="${PIPE}${DIM}effort ${effort}${R}"
[ -n "$session" ] && line3+="${PIPE}${DIM}${session}${R}"

# Print only the lines that carry something, so a pre-first-response session
# does not leave a blank row behind.
out=("$line1")
[ -n "$line2" ] && out+=("$line2")
[ -n "$line3" ] && out+=("$line3")
printf '%s\n' "${out[@]}"
