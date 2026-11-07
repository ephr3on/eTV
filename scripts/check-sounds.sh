#!/usr/bin/env bash
# Fetches every recording eTV can download, the way the TV does, and says what each one is.
ua="eTV-launcher (Android TV)"
grep -ohE '"https://archive\.org/download/[^"]+"' app/src/main/java/app/etv/launcher/AthanSounds.kt | tr -d '"' > /tmp/urls.txt
grep -ohE 'world\("[^"]+"\)' app/src/main/java/app/etv/launcher/AthanSounds.kt | sed -E 's/world\("(.*)"\)/\1/' | while read -r f; do
  python3 -c "import sys,urllib.parse; print('https://archive.org/download/azaan-of-world-mosques-english/'+urllib.parse.quote(sys.argv[1]))" "$f"
done >> /tmp/urls.txt
mkdir -p /tmp/snd
while read -r u; do
  echo "=================================================="
  echo "$u"
  f=/tmp/snd/$(echo "$u" | md5sum | cut -c1-8)
  curl -sSL -m 120 -A "$ua" -o "$f" -D "$f.h" -w 'http=%{http_code} bytes=%{size_download} type=%{content_type} final=%{url_effective} redirects=%{num_redirects}\n' "$u"
  grep -iE '^(HTTP|location|content-type|content-length)' "$f.h" | tr -d '\r'
  file -b "$f"
  ffprobe -v error -show_entries stream=codec_name,codec_long_name,sample_rate,channels,bit_rate:format=format_name,duration -of compact "$f"
  echo "decode errors: $(ffmpeg -v error -i "$f" -f null - 2>&1 | wc -l)"
done < /tmp/urls.txt
