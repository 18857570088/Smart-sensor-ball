# Smart sensor ball Server

Smart sensor ball API service for:

- local user profile bootstrap
- training records
- achievements
- leaderboards

Recommended stack:

- FastAPI
- Uvicorn
- MySQL
- Nginx
- systemd

## Endpoints

- `GET /health`
- `POST /api/v1/user/bootstrap`
- `POST /api/v1/training/session`
- `POST /api/v1/leaderboard`
- `POST /api/v1/user/profile`

## Local structure

- `app/`: API service
- `sql/schema.sql`: MySQL schema
- `deploy/`: systemd / nginx templates
