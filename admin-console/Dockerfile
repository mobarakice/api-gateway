# Build: pnpm + Vite. Runtime: static-web-server (scratch-based, no shell), non-root, read-only.
# /public/config.json is mounted per environment; the CSP comes from the mounted sws.toml.
FROM node:22.13.0-alpine AS build
WORKDIR /src
RUN npm install -g pnpm@10.2.1
COPY package.json pnpm-lock.yaml .npmrc ./
RUN pnpm install --frozen-lockfile
COPY tsconfig.json vite.config.ts eslint.config.js index.html ./
COPY public ./public
COPY src ./src
RUN pnpm build && rm -f dist/config.json

FROM joseluisq/static-web-server:2.36.1
COPY --from=build /src/dist /public
USER 10001:10001
EXPOSE 8080
