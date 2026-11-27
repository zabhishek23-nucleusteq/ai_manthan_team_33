# Build stage
FROM node:26-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY . .
# In-browser mock for /api until the backend endpoints exist (claims: T-016); --build-arg VITE_MOCK=0 for all-real.
ARG VITE_MOCK=1
# Auth separately: 0 = real login against the backend (demo users seeded by T-013); 1 = mock logins.
ARG VITE_MOCK_AUTH=0
ENV VITE_MOCK=$VITE_MOCK VITE_MOCK_AUTH=$VITE_MOCK_AUTH
RUN npm run build

# Serve stage: static files + SPA fallback + /api proxy to the backend service
FROM nginx:1.29-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 80
