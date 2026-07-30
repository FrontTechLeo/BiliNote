ARG BASE_REGISTRY=docker.io
FROM ${BASE_REGISTRY}/library/node:20-alpine AS builder

WORKDIR /app
COPY ./BillNote_admin/package.json ./
COPY ./BillNote_admin/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY ./BillNote_admin/ ./
RUN npm run build

FROM ${BASE_REGISTRY}/library/nginx:1.25-alpine
RUN rm -rf /etc/nginx/conf.d/default.conf
COPY ./BillNote_admin/deploy/default.conf /etc/nginx/conf.d/default.conf
COPY --from=builder /app/build /usr/share/nginx/html
