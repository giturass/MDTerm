#!/bin/sh

set -eu
cd "$(dirname "$0")"

# Use the same cropped MDTerm artwork as the adaptive launcher icon.
# Plain PNG mipmaps also support APK thumbnail readers that cannot inflate XML.
ICON_DATA=$(base64 < ../app/src/main/res/drawable-nodpi/mdterm_icon.png | tr -d '\n')

for DENSITY in mdpi hdpi xhdpi xxhdpi xxxhdpi; do
	case $DENSITY in
		mdpi) SIZE=48;;
		hdpi) SIZE=72;;
		xhdpi) SIZE=96;;
		xxhdpi) SIZE=144;;
		xxxhdpi) SIZE=192;;
	esac

	FOLDER=../app/src/main/res/mipmap-$DENSITY
	mkdir -p "$FOLDER"

	for FILE in ic_launcher ic_launcher_round; do
		PNG=$FOLDER/$FILE.png
		printf '<svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" width="512" height="512"><image width="512" height="512" xlink:href="data:image/png;base64,%s"/></svg>\n' "$ICON_DATA" |
			rsvg-convert -w "$SIZE" -h "$SIZE" -o "$PNG"
	done
done

cp ../app/src/main/res/drawable-nodpi/mdterm_icon.png ../fastlane/metadata/android/en-US/images/icon.png
