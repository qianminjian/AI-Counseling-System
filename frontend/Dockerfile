# MindSafe 前端构建镜像（CI/CD 用）
# 将 student-h5 和 teacher-web 打包为静态资源镜像

FROM node:22-alpine AS builder

WORKDIR /build

# student-h5
COPY student-h5/package.json student-h5/package-lock.json* ./student-h5/
RUN cd student-h5 && npm ci --prefer-offline
COPY student-h5/ ./student-h5/
RUN cd student-h5 && npm run build

# teacher-web
COPY teacher-web/package.json teacher-web/package-lock.json* ./teacher-web/
RUN cd teacher-web && npm ci --prefer-offline
COPY teacher-web/ ./teacher-web/
RUN cd teacher-web && npm run build

# 运行时：仅包含静态文件
FROM alpine:3.20
RUN mkdir -p /app/student /app/teacher
COPY --from=builder /build/student-h5/dist/ /app/student/
COPY --from=builder /build/teacher-web/dist/ /app/teacher/

# 此镜像仅用于 copy 静态文件到 volume，不启动服务
CMD ["echo", "Frontend assets ready at /app/student and /app/teacher"]
