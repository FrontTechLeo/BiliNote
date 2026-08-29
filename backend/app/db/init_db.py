from app.db.models.models import Model
from app.db.models.providers import Provider
from app.db.models.video_tasks import VideoTask, BatchTask, BatchTaskItem, BatchTaskEvent
from app.db.engine import get_engine, Base
from sqlalchemy import inspect, text

def init_db():
    engine = get_engine()

    Base.metadata.create_all(bind=engine)
    # create_all() does not add columns to an existing SQLite database.
    # Keep the lightweight migration here so older desktop databases gain
    # cancellation counts without requiring a destructive reset.
    columns = {column["name"] for column in inspect(engine).get_columns("batch_tasks")}
    if "canceled_count" not in columns:
        with engine.begin() as connection:
            connection.execute(text(
                "ALTER TABLE batch_tasks ADD COLUMN canceled_count INTEGER NOT NULL DEFAULT 0"
            ))
