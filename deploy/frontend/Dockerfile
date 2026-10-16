# ============================================================================
#  前端镜像（多阶段构建）
#  阶段一：Node 20 构建 Vue3 → dist
#  阶段二：Nginx 托管静态文件
#  构建上下文是仓库根目录（见 docker-compose.yml 的 context: ..）
# ============================================================================

# ---------------------------------------------------------------- 构建阶段
FROM node:20-alpine AS builder

WORKDIR /build

# 国内构建可打开这行换镜像源
RUN npm config set registry https://registry.npmmirror.com

COPY frontend/package*.json ./
RUN npm install --no-audit --no-fund

COPY frontend/ ./
RUN npm run build

# ---------------------------------------------------------------- 运行阶段
FROM nginx:1.27-alpine

ENV TZ=Asia/Shanghai

COPY --from=builder /build/dist /usr/share/nginx/html
COPY deploy/frontend/nginx.conf /etc/nginx/conf.d/default.conf

EXPOSE 80

HEALTHCHECK --interval=20s --timeout=4s --retries=5 \
    CMD wget -q -O /dev/null http://localhost/ || exit 1
