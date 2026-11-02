import json
import socket
import unittest

from asistencia_ia.discovery import DISCOVERY_MESSAGE, SERVICE_NAME, DiscoveryResponder


class DiscoveryResponderTest(unittest.TestCase):
    def test_responds_with_reachable_api_url(self) -> None:
        responder = DiscoveryResponder(api_port=9123, discovery_port=0)
        responder.start()
        try:
            with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as client:
                client.settimeout(2)
                client.sendto(
                    DISCOVERY_MESSAGE,
                    ("127.0.0.1", responder.discovery_port),
                )
                payload, _ = client.recvfrom(1024)

            response = json.loads(payload.decode("utf-8"))
            self.assertEqual(response["service"], SERVICE_NAME)
            self.assertEqual(response["url"], "http://127.0.0.1:9123")
        finally:
            responder.stop()


if __name__ == "__main__":
    unittest.main()
