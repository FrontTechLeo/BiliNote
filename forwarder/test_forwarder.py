import os
import socket
import socketserver
import threading
import unittest
from unittest.mock import patch

from forwarder import ForwarderConfig, ForwarderServer


class EchoHandler(socketserver.BaseRequestHandler):
    def handle(self):
        self.request.sendall(b"hello")
        self.request.sendall(self.request.recv(1024).upper())


class ForwarderIntegrationTests(unittest.TestCase):
    def test_forwards_both_directions(self):
        target = socketserver.ThreadingTCPServer(("127.0.0.1", 0), EchoHandler)
        target_thread = threading.Thread(target=target.serve_forever, daemon=True)
        target_thread.start()
        config = ForwarderConfig(
            listen_host="127.0.0.1",
            listen_port=0,
            target_host="127.0.0.1",
            target_port=target.server_address[1],
        )
        forwarder = ForwarderServer(config)
        forwarder_thread = threading.Thread(target=forwarder.serve_forever, daemon=True)
        forwarder_thread.start()
        try:
            with socket.create_connection(forwarder.server_address) as client:
                self.assertEqual(client.recv(5), b"hello")
                client.sendall(b"ping")
                self.assertEqual(client.recv(4), b"PING")
        finally:
            forwarder.shutdown()
            forwarder.server_close()
            target.shutdown()
            target.server_close()


class ForwarderConfigTests(unittest.TestCase):
    def test_defaults(self):
        with patch.dict(os.environ, {}, clear=True):
            self.assertEqual(
                ForwarderConfig.from_environment(),
                ForwarderConfig(),
            )

    def test_environment_overrides(self):
        values = {
            "FORWARDER_LISTEN_HOST": "127.0.0.1",
            "FORWARDER_LISTEN_PORT": "12345",
            "FORWARDER_TARGET_HOST": "example.internal",
            "FORWARDER_TARGET_PORT": "54321",
        }
        with patch.dict(os.environ, values, clear=True):
            self.assertEqual(
                ForwarderConfig.from_environment(),
                ForwarderConfig("127.0.0.1", 12345, "example.internal", 54321),
            )

    def test_invalid_port_is_rejected(self):
        with patch.dict(os.environ, {"FORWARDER_TARGET_PORT": "0"}, clear=True):
            with self.assertRaises(ValueError):
                ForwarderConfig.from_environment()


if __name__ == "__main__":
    unittest.main()
