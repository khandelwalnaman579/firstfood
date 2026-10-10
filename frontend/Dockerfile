# --- Build stage ---
FROM node:24-alpine AS build
WORKDIR /app

COPY package.json package-lock.json ./
RUN npm ci

COPY . .

# Both are BUILD-TIME values (Next inlines NEXT_PUBLIC_* and compiles rewrites into
# the build). Proxy mode: NEXT_PUBLIC_API_BASE_URL="" + BACKEND_ORIGIN=<backend origin>.
# Direct mode (rollback): NEXT_PUBLIC_API_BASE_URL=<backend url>, BACKEND_ORIGIN unused.
# Passing neither fails the build on purpose rather than shipping a broken image.
ARG NEXT_PUBLIC_API_BASE_URL
ARG BACKEND_ORIGIN
ENV NEXT_PUBLIC_API_BASE_URL=${NEXT_PUBLIC_API_BASE_URL}
ENV BACKEND_ORIGIN=${BACKEND_ORIGIN}

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