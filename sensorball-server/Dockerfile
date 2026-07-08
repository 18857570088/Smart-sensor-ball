FROM python:3.11-slim

ENV PYTHONDONTWRITEBYTECODE=1
ENV PYTHONUNBUFFERED=1

WORKDIR /app

RUN python -m pip install --no-cache-dir --upgrade pip

COPY requirements.txt /app/requirements.txt
RUN pip install --no-cache-dir --timeout 60 --retries 5 \
    -i https://pypi.tuna.tsinghua.edu.cn/simple \
    --trusted-host pypi.tuna.tsinghua.edu.cn \
    -r /app/requirements.txt

COPY app /app/app
COPY assets /app/assets

EXPOSE 8013

CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8013"]
