#!/data/data/com.termux/files/usr/bin/sh
# Termux login runs this in place of the default welcome message.
# Prefer the live terminal size; COLUMNS also supports non-interactive previews.
cols=
if [ -t 1 ]; then
    cols=$(stty size <&1 2>/dev/null | awk '{print $2}')
fi
cols=${cols:-${COLUMNS:-33}}
case $cols in
    ''|*[!0-9]*|0) cols=33 ;;
esac

# Explicit cell widths avoid depending on awk Unicode string-length support.
awk -v cols="$cols" '
function draw(first, last, width,    row, i, line, glyph, padding) {
    padding = int((cols - width) / 2)
    for (row = 1; row <= 5; row++) {
        line = ""
        for (i = first; i <= last; i++) {
            split(letters[i], glyph, "[|]")
            line = line (i > first ? " " : "") glyph[row]
        }
        printf "%*s%s\n", padding, "", line
    }
}
BEGIN {
    letters[1] = "█   █|██ ██|█ █ █|█   █|█   █"
    letters[2] = "███ |█  █|█  █|█  █|███ "
    letters[3] = "█████|  █  |  █  |  █  |  █  "
    letters[4] = "████|█   |███ |█   |████"
    letters[5] = "███ |█  █|███ |█ █ |█  █"
    letters[6] = letters[1]
    widths[1] = widths[3] = widths[6] = 5
    widths[2] = widths[4] = widths[5] = 4

    # Leave a spare column to avoid terminal autowrap at the right edge.
    available = cols > 1 ? cols - 1 : 1
    if (available < 5) {
        for (i = 1; i <= 6; i++) print substr("MDTERM", i, 1)
        exit
    }
    first = 1
    width = 0
    for (i = 1; i <= 6; i++) {
        next_width = width + (i > first ? 1 : 0) + widths[i]
        if (next_width > available) {
            draw(first, i - 1, width)
            print ""
            first = i
            width = widths[i]
        } else {
            width = next_width
        }
    }
    draw(first, 6, width)
}'

printf '\n'
printf '%s\n' \
    'Welcome to MDTerm !' \
    'GitHub ：https://github.com/giturass/MDTerm' \
    'Addons ：https://github.com/giturass/MDTermaddon'
