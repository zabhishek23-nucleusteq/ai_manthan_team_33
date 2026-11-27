# Build stage
FROM node:26-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY . .
# Mock auth until backend auth (T-004) is merged; then build with --build-arg VITE_MOCK=0.
ARG VITE_MOCK=1
ENV VITE_MOCK=$VITE_MOCK
RUN npm run build

# Serve stage: static files + SPA fallback + /api proxy to the backend service
FROM nginx:1.29-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 80
