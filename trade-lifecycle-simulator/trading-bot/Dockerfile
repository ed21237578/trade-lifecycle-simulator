FROM python:3.12-slim
RUN useradd --system --uid 1001 app
WORKDIR /app
COPY *.py ./
USER app

CMD ["python", "bot.py"]
