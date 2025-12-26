import json
from mitmproxy import http

class PacketLogger:
    def request(self, flow: http.HTTPFlow):
        print(f"[Request] {flow.request.url}")

    def response(self, flow: http.HTTPFlow):
        print(f"[Response] {flow.request.url} - {flow.response.headers.get('content-type', 'unknown')}")
        
        # Log EVERYTHING to debug
        try:
            with open("captured_packets.json", "a") as f:
                entry = {
                    "url": flow.request.url,
                    "method": flow.request.method,
                    "content_type": flow.response.headers.get("content-type", ""),
                    "response_preview": str(flow.response.content[:200]) # Preview first 200 bytes
                }
                f.write(json.dumps(entry) + "\n")
        except Exception as e:
            print(f"[Error writing file] {e}")

addons = [
    PacketLogger()
]
