import json
import socket
import threading


DISCOVERY_MESSAGE = b"ASISTENCIA_IA_DISCOVER_V1"
DISCOVERY_PORT = 8001
SERVICE_NAME = "asistencia_ia"


def _local_ip_for_peer(peer_ip: str) -> str:
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as probe:
        probe.connect((peer_ip, 9))
        return str(probe.getsockname()[0])


class DiscoveryResponder:
    def __init__(self, api_port: int, discovery_port: int = DISCOVERY_PORT):
        self.api_port = api_port
        self.discovery_port = discovery_port
        self._socket: socket.socket | None = None
        self._thread: threading.Thread | None = None
        self._stopped = threading.Event()

    def start(self) -> None:
        if self._thread is not None:
            return

        responder_socket = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        responder_socket.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        responder_socket.bind(("", self.discovery_port))
        responder_socket.settimeout(0.5)
        self.discovery_port = int(responder_socket.getsockname()[1])
        self._socket = responder_socket
        self._stopped.clear()
        self._thread = threading.Thread(
            target=self._serve,
            name="asistencia-api-discovery",
            daemon=True,
        )
        self._thread.start()

    def stop(self) -> None:
        self._stopped.set()
        if self._socket is not None:
            self._socket.close()
        if self._thread is not None:
            self._thread.join(timeout=1.5)
        self._socket = None
        self._thread = None

    def _serve(self) -> None:
        responder_socket = self._socket
        if responder_socket is None:
            return

        while not self._stopped.is_set():
            try:
                message, sender = responder_socket.recvfrom(1024)
            except socket.timeout:
                continue
            except OSError:
                break

            if message.strip() != DISCOVERY_MESSAGE:
                continue

            try:
                local_ip = _local_ip_for_peer(sender[0])
                response = json.dumps(
                    {
                        "service": SERVICE_NAME,
                        "url": f"http://{local_ip}:{self.api_port}",
                    }
                ).encode("utf-8")
                responder_socket.sendto(response, sender)
            except OSError:
                continue
