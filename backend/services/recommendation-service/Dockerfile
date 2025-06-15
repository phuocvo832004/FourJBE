FROM python:3.9-slim

WORKDIR /app

RUN apt-get update && apt-get install -y curl libgomp1 && rm -rf /var/lib/apt/lists/*

ENV PYTHONUNBUFFERED=1

COPY requirements.txt requirements.txt
RUN pip install -r requirements.txt

COPY ./app ./app

EXPOSE 8090

CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8090", "--log-level", "debug"]
