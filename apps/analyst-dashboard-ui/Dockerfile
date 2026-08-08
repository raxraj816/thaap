# Analyst workspace image: build the SPA, serve it from nginx.
#
# Base images are pinned by digest for the same reason as payments-api — a rebuild of a given
# commit must produce the same runtime, and a floating tag makes that unprovable.

# ---------------------------------------------------------------------------------------------
# Stage 1 — build
# ---------------------------------------------------------------------------------------------
# glibc rather than Alpine. npm records optional platform-specific dependencies only for the
# platform the lockfile was generated on, and a lockfile written on macOS has no musl variants —
# so `npm ci` on Alpine fails on missing @emnapi packages that exist purely as the musl fallback.
# Building on glibc keeps one lockfile valid for both the developer machine and the image.
FROM node:24-bookworm-slim@sha256:3638d9a6fe4030bd716be989438248074489337ba3275657f93595428be4fc03 AS build

WORKDIR /build

# `npm ci` rather than `npm install`, and the lockfile is copied on its own layer so a source
# change does not reinstall. ci fails when package.json and the lockfile disagree, which is the
# behaviour that makes the dependency pins in package.json meaningful rather than decorative.
COPY package.json package-lock.json ./
RUN npm ci

COPY tsconfig.json vite.config.ts ./
COPY src ./src
COPY index.html ./

# `npm run build` runs `tsc --noEmit && vite build`, so a type error fails the image rather than
# shipping a bundle nobody type-checked.
RUN npm run build

# ---------------------------------------------------------------------------------------------
# Stage 2 — serve
# ---------------------------------------------------------------------------------------------
FROM nginx:1.27-alpine@sha256:62223d644fa234c3a1cc785ee14242ec47a77364226f1c811d2f669f96dc2ac8 AS runtime

COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /build/dist /usr/share/nginx/html

# The stock image runs workers as nginx but the master as root, and listens on 80. Neither is
# needed: the container listens on 8081 unprivileged and runs entirely as a non-root user.
# The pid directive is rewritten by whole line rather than by substituting a path string: the
# stock config has said /var/run/nginx.pid and /run/nginx.pid in different releases, and a
# substitution that silently fails to match leaves nginx trying to write to a root-owned
# directory — which fails at startup, not at build, and only when running as non-root.
RUN sed -i '/^user /d' /etc/nginx/nginx.conf \
    && sed -i 's#^pid .*#pid /tmp/nginx.pid;#' /etc/nginx/nginx.conf \
    && grep -q '^pid /tmp/nginx.pid;$' /etc/nginx/nginx.conf \
    && chown -R nginx:nginx /usr/share/nginx/html /var/cache/nginx

USER nginx:nginx

EXPOSE 8081

HEALTHCHECK --interval=15s --timeout=3s --start-period=10s --retries=3 \
    CMD wget --quiet --spider http://127.0.0.1:8081/healthz || exit 1

CMD ["nginx", "-g", "daemon off;"]
