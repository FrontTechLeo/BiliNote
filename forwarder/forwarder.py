#!/usr/bin/env python3
"""Small dependency-free TCP port forwarder."""

import logging
import os
import socket
import socketserver
import threading
from dataclasses import dataclass


LOGGER = logging.getLogger("forwarder")


@dataclass(frozen=True)
class ForwarderConfig:
    listen_host: str = "0.0.0.0"
    listen_port: int = 3489
    target_host: str = "192.168.137.37"
    target_port: int = 3389

    @classmethod
    def from_environment(cls) -> "ForwarderConfig":
        return cls(
            listen_host=os.getenv("FORWARDER_LISTEN_HOST", cls.listen_host),
            listen_port=_read_port("FORWARDER_LISTEN_PORT", cls.listen_port),
            target_host=os.getenv("FORWARDER_TARGET_HOST", cls.target_host),
            target_port=_read_port("FORWARDER_TARGET_PORT", cls.target_port),
        )


def _read_port(name: str, default: int) -> int:
    value = os.getenv(name)
    if value is None or not value.strip():
        return default
    try:
        port = int(value)
    except ValueError as exc:
        raise ValueError(f"{name} must be an integer port") from exc
    if not 1 <= port <= 65535:
        raise ValueError(f"{name} must be between 1 and 65535")
    return port


def _pipe(source: socket.socket, destination: socket.socket) -> None:
    try:
        while True:
            data = source.recv(65536)
            if not data:
                break
            destination.sendall(data)
    except (ConnectionError, OSError):
        LOGGER.debug("TCP pipe closed", exc_info=True)
    finally:
        try:
            destination.shutdown(socket.SHUT_WR)
        except OSError:
            pass


class ForwarderHandler(socketserver.BaseRequestHandler):
    def handle(self) -> None:
        config: ForwarderConfig = self.server.config  # type: ignore[attr-defined]
        client = self.request
        peer = client.getpeername()
        LOGGER.info("connection from %s to %s:%s", peer, config.target_host, config.target_port)
        try:
            with socket.create_connection((config.target_host, config.target_port)) as target:
                threads = [
                    threading.Thread(target=_pipe, args=(client, target), daemon=True),
                    threading.Thread(target=_pipe, args=(target, client), daemon=True),
                ]
                for thread in threads:
                    thread.start()
                for thread in threads:
                    thread.join()
        except OSError:
            LOGGER.warning("could not connect to target %s:%s", config.target_host, config.target_port, exc_info=True)
        finally:
            LOGGER.info("connection closed from %s", peer)


class ForwarderServer(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True

    def __init__(self, config: ForwarderConfig) -> None:
        self.config = config
        super().__init__((config.listen_host, config.listen_port), ForwarderHandler)


def main() -> None:
    logging.basicConfig(
        level=os.getenv("FORWARDER_LOG_LEVEL", "INFO").upper(),
        format="%(asctime)s %(levelname)s %(message)s",
    )
    config = ForwarderConfig.from_environment()
    LOGGER.info(
        "listening on %s:%s and forwarding to %s:%s",
        config.listen_host,
        config.listen_port,
        config.target_host,
        config.target_port,
    )
    with ForwarderServer(config) as server:
        server.serve_forever()


if __name__ == "__main__":
    main()
