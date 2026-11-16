FROM python:3.12-slim
RUN useradd --system --uid 1001 app
WORKDIR /app
COPY *.py ./
USER app
EXPOSE 8001
CMD ["python", "service.py"]
