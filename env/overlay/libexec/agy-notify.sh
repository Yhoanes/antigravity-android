#!/data/data/com.agy/files/usr/bin/bash
# agy-notify - Post and manage Android notifications with original Antigravity logo
# Zero emojis, purely functional and cleanly formatted.

set -e

: "${PREFIX:=/data/data/com.agy/files/usr}"
FILES_DIR="$(cd "$(dirname "$PREFIX")" 2>/dev/null && pwd || echo "/data/data/com.agy/files")"

find_rish() {
    local cand
    for cand in "$FILES_DIR/runtime/bin/rish" "$PREFIX/bin/rish" "$FILES_DIR/bin/rish"; do
        if [ -x "$cand" ]; then
            printf '%s\n' "$cand"
            return 0
        fi
    done
    command -v rish 2>/dev/null || true
}

RISH_BIN="$(find_rish)"

USAGE="Usage: agy-notify [post|cancel|list] [options]
Commands:
  post    -t <title> [-s <subtitle>] [-m <message>] [--tag <tag>]
  cancel  <tag>
  list    Show active notifications
"

CMD="${1:-post}"
shift || true

TITLE="Antigravity"
SUBTITLE=""
MESSAGE=""
TAG="agy_$(date +%s)"

while [[ $# -gt 0 ]]; do
    case "$1" in
        -t|--title)
            TITLE="$2"
            shift 2
            ;;
        -s|--subtitle)
            SUBTITLE="$2"
            shift 2
            ;;
        -m|--message)
            MESSAGE="$2"
            shift 2
            ;;
        --tag)
            TAG="$2"
            shift 2
            ;;
        *)
            if [ -z "$MESSAGE" ]; then
                MESSAGE="$1"
            fi
            shift
            ;;
    esac
done

if [ "$CMD" = "post" ]; then
    if [ -z "$MESSAGE" ] && [ -n "$TITLE" ]; then
        MESSAGE="$TITLE"
        TITLE="Antigravity"
    fi

    BODY_TEXT="$MESSAGE"
    if [ -n "$SUBTITLE" ]; then
        BODY_TEXT="[$SUBTITLE] $MESSAGE"
    fi

    if [ -n "$RISH_BIN" ] && [ -x "$RISH_BIN" ]; then
        # Cache bundled Antigravity logo into /data/local/tmp and post in a single rish call
        LOGO_CACHE="/data/local/tmp/agy_logo.png"
        SRC_LOGO="$PREFIX/share/antigravity/logo.png"
        if [ -f "$SRC_LOGO" ]; then
            "$RISH_BIN" -c "
                if [ ! -s '$LOGO_CACHE' ]; then
                    cat > '$LOGO_CACHE' 2>/dev/null && chmod 644 '$LOGO_CACHE' 2>/dev/null || true
                fi
                ICON_OPT=''
                [ -s '$LOGO_CACHE' ] && ICON_OPT='-I file://$LOGO_CACHE'
                cmd notification post -S bigtext \$ICON_OPT -t $(printf %q "$TITLE") $(printf %q "$TAG") $(printf %q "$BODY_TEXT")
            " < "$SRC_LOGO" 2>/dev/null && exit 0
        else
            "$RISH_BIN" -c "cmd notification post -S bigtext -t $(printf %q "$TITLE") $(printf %q "$TAG") $(printf %q "$BODY_TEXT")" 2>/dev/null && exit 0
        fi
    fi

    # Fallback if without rish
    cmd notification post -S bigtext -t "$TITLE" "$TAG" "$BODY_TEXT" 2>/dev/null && exit 0 || true
    echo "[agy-notify] notification queued: $TITLE - $MESSAGE"

elif [ "$CMD" = "cancel" ]; then
    if [ -n "$RISH_BIN" ] && [ -x "$RISH_BIN" ]; then
        "$RISH_BIN" -c "cmd notification cancel $(printf %q "$TAG")" 2>/dev/null || true
    fi

elif [ "$CMD" = "list" ]; then
    if [ -n "$RISH_BIN" ] && [ -x "$RISH_BIN" ]; then
        "$RISH_BIN" -c "dumpsys notification --noredact" 2>/dev/null | grep -E "pkg=|android.title=|android.text=" | head -n 40
    else
        echo "[agy-notify] rish unavailable to inspect system notification tray"
    fi

else
    echo "$USAGE"
    exit 1
fi

