"""Minimal private diagnostic receiver. Run behind HTTPS nginx, bound to loopback."""
import argparse
import hashlib
import hmac
import io
import json
import os
from pathlib import Path
import re
import threading
import time
import zipfile
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

MAX_BODY = 4 * 1024 * 1024
MAX_EXPANDED = 6 * 1024 * 1024
ENTRY = re.compile(r"report\.json|logs/(?:app|memory)\.log|farm/[A-Za-z0-9_-]+/(?:state\.json|timing\.jsonl|actions\.jsonl)")

def validate(data):
    try:
        with zipfile.ZipFile(io.BytesIO(data)) as archive:
            entries = archive.infolist()
            if not entries or len(entries) > 16 or len({e.filename for e in entries}) != len(entries):
                raise ValueError('invalid entry count')
            if sum(e.file_size for e in entries) > MAX_EXPANDED:
                raise ValueError('expanded limit')
            for entry in entries:
                if not ENTRY.fullmatch(entry.filename) or entry.flag_bits & 1 or entry.is_dir():
                    raise ValueError('entry not allowed')
                archive.read(entry).decode('utf-8', errors='strict')
            report = json.loads(archive.read('report.json'))
            if not isinstance(report, dict) or report.get('format') != 1:
                raise ValueError('invalid format')
    except (zipfile.BadZipFile, KeyError, UnicodeError, RuntimeError, json.JSONDecodeError, NotImplementedError) as exc:
        raise ValueError('invalid report') from exc

class Receiver(ThreadingHTTPServer):
    daemon_threads = True
    def __init__(self, address, directory, token, quota=1024*1024*1024):
        if len(token) < 32:
            raise ValueError('token must have at least 32 characters')
        self.directory = Path(directory)
        self.directory.mkdir(parents=True, exist_ok=True, mode=0o700)
        self.token = token
        self.quota = quota
        self.lock = threading.Lock()
        self.last_cleanup = 0
        super().__init__(address, Handler)

    def service_actions(self):
        if time.time()-self.last_cleanup < 3600:
            return
        with self.lock:
            now = time.time()
            for report in self.directory.glob('jh-*.zip'):
                try:
                    if now-report.stat().st_mtime > 14*86400:
                        report.unlink()
                except OSError:
                    pass
            self.last_cleanup = now

class Handler(BaseHTTPRequestHandler):
    server_version = 'JiangHuDiagnostics/1'
    def setup(self):
        super().setup()
        self.connection.settimeout(30)

    def log_message(self, *_):
        pass  # Do not log payloads, Authorization or query strings.

    def reply(self, status, value):
        body = json.dumps(value).encode('utf-8')
        self.send_response(status)
        self.send_header('Content-Type', 'application/json; charset=utf-8')
        self.send_header('Content-Length', str(len(body)))
        self.send_header('Cache-Control', 'no-store')
        self.send_header('Connection', 'close')
        self.end_headers()
        self.wfile.write(body)
        self.close_connection = True

    def do_GET(self):
        self.reply(200 if self.path == '/health' else 404,
                   {'status': 'ok'} if self.path == '/health' else {'error': 'not_found'})

    def do_POST(self):
        if self.path != '/jianghu/report':
            return self.reply(404, {'error': 'not_found'})
        received = self.headers.get('Authorization', '')
        if not hmac.compare_digest(received.encode(), ('Bearer '+self.server.token).encode()):
            return self.reply(401, {'error': 'unauthorized'})
        if self.headers.get('Content-Type') != 'application/zip':
            return self.reply(415, {'error': 'content_type'})
        length = self.headers.get('Content-Length', '')
        if not length.isdigit() or not 1 <= int(length) <= MAX_BODY or self.headers.get('Transfer-Encoding'):
            return self.reply(413, {'error': 'size_limit'})
        try:
            data = self.rfile.read(int(length))
            if len(data) != int(length):
                return self.reply(400, {'error': 'incomplete'})
            validate(data)
        except (ValueError, EOFError, OSError):
            return self.reply(400, {'error': 'invalid_report'})
        report_id = 'jh-' + hashlib.sha256(data).hexdigest()[:24]
        try:
            with self.server.lock:
                now = time.time()
                files = list(self.server.directory.glob('jh-*.zip'))
                for old in files:
                    if now-old.stat().st_mtime > 14*86400:
                        old.unlink()
                target = self.server.directory/(report_id+'.zip')
                if not target.exists():
                    used = sum(p.stat().st_size for p in self.server.directory.glob('jh-*.zip'))
                    if used+len(data) > self.server.quota:
                        return self.reply(507, {'error': 'quota_exceeded'})
                    partial = target.with_suffix('.part')
                    with partial.open('wb') as output:
                        output.write(data)
                    partial.chmod(0o600)
                    partial.replace(target)
        except OSError:
            return self.reply(503, {'error': 'storage_unavailable'})
        self.reply(201, {'report_id': report_id})

if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--port', type=int, default=28777)
    parser.add_argument('--directory', default='/var/lib/jianghu-diagnostics/reports')
    args = parser.parse_args()
    os.umask(0o077)
    Receiver(('127.0.0.1', args.port), args.directory, os.environ['JH_UPLOAD_TOKEN']).serve_forever()
