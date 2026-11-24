#!/usr/bin/env python3
"""Static policy check; complements, and does not replace, real-device power observation."""
from pathlib import Path
import xml.etree.ElementTree as ET
root=Path(__file__).resolve().parents[1]/'app/src'
manifest=ET.parse(root/'main/AndroidManifest.xml').getroot()
a='{http://schemas.android.com/apk/res/android}'
permissions={n.get(a+'name') for n in manifest.findall('uses-permission')}
assert 'android.permission.FOREGROUND_SERVICE' in permissions
assert 'android.permission.SCHEDULE_EXACT_ALARM' not in permissions
app=manifest.find('application')
for tag in ['service','receiver']:
 for n in app.findall(tag):
  if 'SubscriberService' in n.get(a+'name',''):assert n.get(a+'enabled')=='false'
main=(root/'main/java/io/heckel/ntfy/ui/MainActivity.kt').read_text()
assert 'enqueueUniquePeriodicWork(' not in main
manager=(root/'main/java/io/heckel/ntfy/service/SubscriberServiceManager.kt').read_text()
assert 'startForegroundService(' not in manager and 'enqueueUniqueWork(' not in manager
service=(root/'play/java/io/heckel/ntfy/firebase/FirebaseService.kt').read_text()
keepalive=service.split('private fun handleKeepalive',1)[1].split('private fun handlePollRequest',1)[0]
assert 'sendBroadcast(' not in keepalive
direct=(root/'main/java/io/heckel/ntfy/qiuqiu/DirectService.kt').read_text()
assert 'if(mode=="fcm"){stopSelf();return START_NOT_STICKY}' in direct
assert 'enqueueUniquePeriodicWork(' not in direct and 'WakeLock' not in direct
print('Static background policy passed: FCM defaults to no foreground connection; optional user-enabled direct service; no periodic polling/exact alarms')
