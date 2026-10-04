Write-Host "Clearing logcat buffer..." -ForegroundColor Yellow
adb logcat -c
Write-Host "Streaming [BT-FORENSIC] and Bluetooth logs... (Press Ctrl+C to stop)" -ForegroundColor Cyan
adb logcat -v time -s BT-FORENSIC:V AndroidBluetoothRfcommTransport:V PravahRfcomm:V BluetoothSocket:V bt_rfcomm:V *:E
