FROM node:22.16.0-alpine3.22 AS build
WORKDIR /build
COPY package*.json ./
RUN npm ci
COPY . .
RUN npm run build
FROM nginx:1.28.0-alpine3.21
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /build/dist /usr/share/nginx/html
EXPOSE 80
