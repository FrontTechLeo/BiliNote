from sqlalchemy import Column, Integer, String, DateTime, Text, ForeignKey, func
from sqlalchemy.orm import declarative_base

from app.db.engine import Base


class VideoTask(Base):
    __tablename__ = "video_tasks"

    id = Column(Integer, primary_key=True, autoincrement=True)
    video_id = Column(String, nullable=False)
    platform = Column(String, nullable=False)
    task_id = Column(String, unique=True, nullable=False)
    created_at = Column(DateTime, server_default=func.now())


class BatchTask(Base):
    __tablename__ = "batch_tasks"

    id = Column(String, primary_key=True)
    title = Column(String, nullable=False)
    source_type = Column(String, nullable=False)
    source_value = Column(Text, nullable=True)
    config_json = Column(Text, nullable=False)
    status = Column(String, nullable=False, default="PENDING")
    total_count = Column(Integer, nullable=False, default=0)
    completed_count = Column(Integer, nullable=False, default=0)
    failed_count = Column(Integer, nullable=False, default=0)
    canceled_count = Column(Integer, nullable=False, default=0)
    running_count = Column(Integer, nullable=False, default=0)
    created_at = Column(DateTime, server_default=func.now())
    started_at = Column(DateTime, nullable=True)
    finished_at = Column(DateTime, nullable=True)


class BatchTaskItem(Base):
    __tablename__ = "batch_task_items"

    id = Column(String, primary_key=True)
    batch_id = Column(String, ForeignKey("batch_tasks.id"), nullable=False, index=True)
    task_id = Column(String, nullable=True, unique=True, index=True)
    source_url = Column(Text, nullable=False)
    platform = Column(String, nullable=False)
    title = Column(String, nullable=False)
    position = Column(Integer, nullable=False)
    status = Column(String, nullable=False, default="PENDING")
    stage = Column(String, nullable=True)
    message = Column(Text, nullable=True)
    attempts = Column(Integer, nullable=False, default=0)
    created_at = Column(DateTime, server_default=func.now())
    updated_at = Column(DateTime, server_default=func.now(), onupdate=func.now())


class BatchTaskEvent(Base):
    __tablename__ = "batch_task_events"

    id = Column(Integer, primary_key=True, autoincrement=True)
    batch_item_id = Column(String, ForeignKey("batch_task_items.id"), nullable=False, index=True)
    status = Column(String, nullable=False)
    message = Column(Text, nullable=True)
    created_at = Column(DateTime, server_default=func.now())
