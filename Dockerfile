FROM node:20-alpine

# Install FFmpeg and SSL certificates
RUN apk add --no-cache ffmpeg ca-certificates

WORKDIR /app

# Copy package files and install dependencies
COPY server/package*.json ./server/
WORKDIR /app/server
RUN npm install --omit=dev

# Copy application files
WORKDIR /app
COPY client ./client
COPY server ./server

WORKDIR /app/server

ENV PORT=10000
EXPOSE 10000

CMD ["node", "server.js"]
