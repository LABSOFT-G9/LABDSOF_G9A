"""ai-triage skeleton: Flask app with a health endpoint (pipeline testing)."""
from flask import Flask


def create_app() -> Flask:
    app = Flask(__name__)

    @app.get("/")
    def index():
        return "ai-triage up"

    @app.get("/health")
    def health():
        return {"status": "ok"}

    return app


app = create_app()
