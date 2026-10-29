"""Generate update.json for a GitHub Release; upload it beside the signed APK."""
import argparse
import hashlib
import json
from pathlib import Path

def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('apk',type=Path)
    parser.add_argument('--metadata',type=Path,required=True)
    parser.add_argument('--output',type=Path,required=True)
    args=parser.parse_args()
    meta=json.loads(args.metadata.read_text(encoding='utf-8'))['elements'][0]
    result={'versionCode':meta['versionCode'],'versionName':meta['versionName'],
            'apk':args.apk.name,'size':args.apk.stat().st_size,
            'sha256':hashlib.file_digest(args.apk.open('rb'),'sha256').hexdigest()}
    args.output.write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print('Release manifest generated:',args.output)

if __name__=='__main__':main()
