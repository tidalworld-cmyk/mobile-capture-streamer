FROM node:20-bullseye-slim

# Install FFmpeg and certificates for RTMP streaming
RUN apt-get update && \
    apt-get install -y --no-install-recommends ffmpeg ca-certificates && \
    rm -rf /var/lib/apt/lists/*

WORKDIR /app

# Copy server package and install dependencies
COPY server/package*.json ./server/
WORKDIR /app/server
RUN npm install --production

# Copy source code
WORKDIR /app
COPY client ./client
COPY server ./server

WORKDIR /app/server

ENV PORT=10000
EXPOSE 10000

CMD ["node", "server.js"]
