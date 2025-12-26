#!/bin/bash
# install_cert_root.sh

CERT_PATH=~/.mitmproxy/mitmproxy-ca-cert.pem

if [ ! -f "$CERT_PATH" ]; then
    echo "Certificate not found at $CERT_PATH"
    exit 1
fi

echo "Calculating certificate hash..."
HASH=$(openssl x509 -inform PEM -subject_hash_old -in $CERT_PATH | head -1)
echo "Hash: $HASH"

target_filename="${HASH}.0"

echo "Rooting adb..."
adb root
adb wait-for-device

echo "Remounting system partition..."
adb remount

echo "Pushing certificate to /system/etc/security/cacerts/$target_filename..."
adb push $CERT_PATH /system/etc/security/cacerts/$target_filename
adb shell chmod 644 /system/etc/security/cacerts/$target_filename

echo "Rebooting emulator to apply changes..."
adb reboot
adb wait-for-device
echo "Certificate installed successfully!"
