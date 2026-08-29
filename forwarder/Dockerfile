FROM python:3.11-slim

WORKDIR /app
COPY forwarder/forwarder.py /app/forwarder.py

ENV FORWARDER_LISTEN_HOST=0.0.0.0 \
    FORWARDER_LISTEN_PORT=3489 \
    FORWARDER_TARGET_HOST=192.168.137.37 \
    FORWARDER_TARGET_PORT=3389

CMD ["python", "/app/forwarder.py"]
