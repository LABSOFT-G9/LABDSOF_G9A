# Build stage (node:lts-alpine, digest read 2026-11-15)
FROM node:lts-alpine@sha256:6bd9ab43c0ec0190ca7b2d193906cb8b7edf6e7f4afa43ddeccd6452710bffb5 AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
# Which adapters the bundle wires (src/app/container.ts). "mock" until the backend and Keycloak are deployed;
# build with --build-arg VITE_ADAPTERS=http to use the real API behind Traefik.
ARG VITE_ADAPTERS=mock
ENV VITE_ADAPTERS=${VITE_ADAPTERS}
RUN npm run build

# Runtime stage (nginx:stable-alpine, digest read 2026-11-15)
FROM nginx:1.30.7-alpine@sha256:caf4e1e032a20f9c3c5e28fc7f25ffd768ad8677b4975c54193075997b3a97e3
# Apply Alpine security updates released after the base image was built.
RUN apk upgrade --no-cache
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 80
