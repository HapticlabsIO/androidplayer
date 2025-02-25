#!/bin/bash

# Get the directory of the script itself
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Set the directory where the POM files are located, relative to the script's location
STAGING_DIR="$SCRIPT_DIR/build/staging-deploy/io/hapticlabs/hapticlabsplayer"

# Function to calculate and update checksum files
update_checksums() {
  local file="$1"
  local md5=$(md5sum "$file" | awk '{print $1}')
  local sha1=$(shasum -a 1 "$file" | awk '{print $1}')
  local sha256=$(shasum -a 256 "$file" | awk '{print $1}')
  local sha512=$(shasum -a 512 "$file" | awk '{print $1}')

  echo "$md5" > "$file.md5"
  echo "$sha1" > "$file.sha1"
  echo "$sha256" > "$file.sha256"
  echo "$sha512" > "$file.sha512"

  echo "Updated checksums for: $file"
}

# Find all POM files in the staging directory
find "$STAGING_DIR" -name "hapticlabsplayer-*.pom" -print0 | while IFS= read -r -d $'\0' pom_file; do
  echo "Processing POM file: $pom_file"

  # Use xmlstarlet to insert the XML snippet
  if ! xmlstarlet ed -L\
  -N x="http://maven.apache.org/POM/4.0.0" \
  -s "/x:project" -t elem -n "build" \
  -s "/x:project/build" -t elem -n "plugins" \
  -s "/x:project/build/plugins" -t elem -n "plugin" \
  -s "/x:project/build/plugins/plugin" -t elem -n "groupId" -v "com.simpligility.maven.plugins" \
  -s "/x:project/build/plugins/plugin" -t elem -n "artifactId" -v "android-maven-plugin" \
  -s "/x:project/build/plugins/plugin" -t elem -n "version" -v "4.6.0" \
  -s "/x:project/build/plugins/plugin" -t elem -n "extensions" -v "true" \
  "$pom_file"; then
    echo "Error: Failed to modify POM file: $pom_file"
    exit 1
  fi

  echo "Modified POM file: $pom_file"

  # Update checksums for the modified POM file
  update_checksums "$pom_file"
done

echo "POM files modified and checksums updated."
