# --- Build stage ---
FROM node:24-alpine AS build
WORKDIR /app

COPY package.json package-lock.json ./
RUN npm ci

COPY . .

# NEXT_PUBLIC_* vars are baked into the JS bundle at build time (see
# README "Frontend API URL: build-time vs. runtime") - they must be
# ARGs available to this RUN step, not just container-runtime ENV vars.
# docker-compose passes this via build.args; a plain `docker build`
# needs --build-arg NEXT_PUBLIC_API_BASE_URL=... or it'll fail fast here
# via lib/env.ts, same as a missing required env var anywhere else in
# this project.
ARG NEXT_PUBLIC_API_BASE_URL
ENV NEXT_PUBLIC_API_BASE_URL=$NEXT_PUBLIC_API_BASE_URL
RUN npm run build

# --- Runtime stage ---
FROM node:24-alpine
WORKDIR /app
ENV NODE_ENV=production

RUN addgroup -S firstfood && adduser -S firstfood -G firstfood
COPY --from=build /app/.next ./.next
COPY --from=build /app/public ./public
COPY --from=build /app/package.json ./package.json
COPY --from=build /app/node_modules ./node_modules

USER firstfood
EXPOSE 3000
CMD ["npm", "run", "start"]
