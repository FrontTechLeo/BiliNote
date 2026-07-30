from typing import Any, Optional

from fastapi import APIRouter, File, HTTPException, UploadFile
from pydantic import BaseModel, Field

from app.services.batch_task_service import batch_task_service
from app.utils.response import ResponseWrapper as R

router = APIRouter()


class BatchPreviewRequest(BaseModel):
    source_url: str


class BatchItemInput(BaseModel):
    title: str
    source_url: str
    platform: str = "bilibili"


class BatchConfig(BaseModel):
    quality: str = "medium"
    model_name: str
    provider_id: str
    screenshot: bool = False
    link: bool = False
    format: list[str] = Field(default_factory=list)
    style: str = "minimal"
    extras: Optional[str] = None
    video_understanding: bool = False
    video_interval: int = 6
    grid_size: list[int] = Field(default_factory=lambda: [2, 2])


class BatchCreateRequest(BaseModel):
    title: str
    source_type: str
    source_value: Optional[str] = None
    config: BatchConfig
    items: list[BatchItemInput]


class BatchExportRequest(BaseModel):
    item_ids: Optional[list[str]] = None


@router.post("/batch/preview/bilibili")
def preview_bilibili(data: BatchPreviewRequest):
    try:
        return R.success(batch_task_service.preview_bilibili(data.source_url))
    except Exception as exc:
        return R.error(msg=str(exc))


@router.post("/batch/preview/local")
def preview_local(files: list[UploadFile] = File(...)):
    try:
        return R.success(batch_task_service.store_local_preview(files))
    except Exception as exc:
        return R.error(msg=str(exc))


@router.post("/batch/tasks")
def create_batch_task(data: BatchCreateRequest):
    try:
        return R.success(batch_task_service.create_task(
            data.title, data.source_type, data.source_value, data.config.model_dump(),
            [item.model_dump() for item in data.items],
        ))
    except ValueError as exc:
        return R.error(msg=str(exc), code=400)


@router.get("/batch/tasks")
def list_batch_tasks(page: int = 1, page_size: int = 20):
    return R.success(batch_task_service.list_tasks(max(page, 1), min(max(page_size, 1), 100)))


@router.get("/batch/tasks/{batch_id}")
def get_batch_task(batch_id: str):
    task = batch_task_service.get_task(batch_id)
    if not task:
        return R.error(msg="批量任务不存在", code=404)
    return R.success(task)


@router.get("/batch/tasks/{batch_id}/items")
def list_batch_items(batch_id: str):
    return R.success(batch_task_service.list_items(batch_id))


@router.get("/batch/tasks/{batch_id}/items/{item_id}/events")
def list_batch_item_events(batch_id: str, item_id: str):
    return R.success(batch_task_service.list_events(item_id))


@router.post("/batch/tasks/{batch_id}/items/{item_id}/retry")
def retry_batch_item(batch_id: str, item_id: str):
    try:
        return R.success(batch_task_service.retry_item(batch_id, item_id))
    except KeyError as exc:
        return R.error(msg=str(exc), code=404)
    except ValueError as exc:
        return R.error(msg=str(exc), code=400)


@router.get("/batch/tasks/{batch_id}/export")
def export_batch_task(batch_id: str):
    try:
        return batch_task_service.export_items(batch_id)
    except KeyError as exc:
        raise HTTPException(status_code=404, detail=str(exc))
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc))


@router.post("/batch/tasks/{batch_id}/export")
def export_selected_batch_items(batch_id: str, data: BatchExportRequest):
    try:
        return batch_task_service.export_items(batch_id, data.item_ids)
    except KeyError as exc:
        raise HTTPException(status_code=404, detail=str(exc))
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc))


@router.get("/batch/tasks/{batch_id}/items/{item_id}/export")
def export_batch_item(batch_id: str, item_id: str):
    try:
        return batch_task_service.export_items(batch_id, [item_id])
    except KeyError as exc:
        raise HTTPException(status_code=404, detail=str(exc))
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc))
