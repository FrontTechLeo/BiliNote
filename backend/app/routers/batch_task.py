from typing import Any, Optional

from fastapi import APIRouter, File, HTTPException, UploadFile
from pydantic import BaseModel, Field, field_validator

from app.services.batch_task_service import batch_task_service
from app.utils.response import ResponseWrapper as R
from app.utils.video_reader import DEFAULT_IMAGE_COMPRESSION, normalize_image_compression

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
    image_compression: str = DEFAULT_IMAGE_COMPRESSION

    @field_validator("image_compression")
    @classmethod
    def validate_image_compression(cls, value: str) -> str:
        return normalize_image_compression(value)


class BatchCreateRequest(BaseModel):
    title: Optional[str] = None
    source_type: str
    source_value: Optional[str] = None
    config: BatchConfig
    items: list[BatchItemInput]


class BatchExportRequest(BaseModel):
    item_ids: Optional[list[str]] = None


class BatchAppendRequest(BaseModel):
    items: list[BatchItemInput]


class BatchUpdateRequest(BaseModel):
    title: Optional[str] = None
    config: Optional[BatchConfig] = None


class BatchDeleteItemsRequest(BaseModel):
    item_ids: list[str]


class BatchItemsActionRequest(BaseModel):
    """批量启动/停止子任务时的请求体。"""

    item_ids: list[str]


class LocalPathsPreviewRequest(BaseModel):
    paths: list[str]


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


@router.post("/batch/preview/local_paths")
def preview_local_paths(data: LocalPathsPreviewRequest):
    """PC 客户端直读模式：直接扫描本地磁盘文件/文件夹，零上传。"""
    try:
        return R.success(batch_task_service.store_local_paths_preview(data.paths))
    except Exception as exc:
        return R.error(msg=str(exc))


@router.post("/batch/tasks")
def create_batch_task(data: BatchCreateRequest):
    try:
        return R.success(batch_task_service.create_task(
            data.title or "", data.source_type, data.source_value, data.config.model_dump(),
            [item.model_dump() for item in data.items],
        ))
    except ValueError as exc:
        return R.error(msg=str(exc), code=400)


@router.patch("/batch/tasks/{batch_id}")
def update_batch_task(batch_id: str, data: BatchUpdateRequest):
    try:
        return R.success(batch_task_service.update_task(
            batch_id,
            title=data.title,
            config=data.config.model_dump() if data.config is not None else None,
        ))
    except KeyError as exc:
        return R.error(msg=str(exc), code=404)
    except ValueError as exc:
        return R.error(msg=str(exc), code=400)


@router.post("/batch/tasks/{batch_id}/preview/bilibili")
def preview_append_bilibili(batch_id: str, data: BatchPreviewRequest):
    try:
        return R.success(batch_task_service.preview_append_bilibili(batch_id, data.source_url))
    except KeyError as exc:
        return R.error(msg=str(exc), code=404)
    except ValueError as exc:
        return R.error(msg=str(exc), code=400)


@router.post("/batch/tasks/{batch_id}/preview/local")
def preview_append_local(batch_id: str, files: list[UploadFile] = File(...)):
    try:
        return R.success(batch_task_service.preview_append_local(batch_id, files))
    except KeyError as exc:
        return R.error(msg=str(exc), code=404)
    except ValueError as exc:
        return R.error(msg=str(exc), code=400)


@router.post("/batch/tasks/{batch_id}/preview/local_paths")
def preview_append_local_paths(batch_id: str, data: LocalPathsPreviewRequest):
    """PC 客户端直读模式：向已有批量任务追加本地磁盘视频。"""
    try:
        return R.success(batch_task_service.preview_append_local_paths(batch_id, data.paths))
    except KeyError as exc:
        return R.error(msg=str(exc), code=404)
    except ValueError as exc:
        return R.error(msg=str(exc), code=400)


@router.post("/batch/tasks/{batch_id}/items")
def append_batch_items(batch_id: str, data: BatchAppendRequest):
    try:
        return R.success(batch_task_service.append_items(
            batch_id,
            [item.model_dump() for item in data.items],
        ))
    except KeyError as exc:
        return R.error(msg=str(exc), code=404)
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


@router.post("/batch/tasks/{batch_id}/items/batch_retry")
def retry_batch_items(batch_id: str, data: BatchItemsActionRequest):
    try:
        return R.success(batch_task_service.retry_items(batch_id, data.item_ids))
    except KeyError as exc:
        return R.error(msg=str(exc), code=404)
    except ValueError as exc:
        return R.error(msg=str(exc), code=400)


@router.post("/batch/tasks/{batch_id}/items/{item_id}/cancel")
def cancel_batch_item(batch_id: str, item_id: str):
    try:
        return R.success(batch_task_service.cancel_item(batch_id, item_id))
    except KeyError as exc:
        return R.error(msg=str(exc), code=404)
    except ValueError as exc:
        return R.error(msg=str(exc), code=400)


@router.post("/batch/tasks/{batch_id}/items/batch_cancel")
def cancel_batch_items(batch_id: str, data: BatchItemsActionRequest):
    try:
        return R.success(batch_task_service.cancel_items(batch_id, data.item_ids))
    except KeyError as exc:
        return R.error(msg=str(exc), code=404)
    except ValueError as exc:
        return R.error(msg=str(exc), code=400)


@router.delete("/batch/tasks/{batch_id}/items/{item_id}")
def delete_batch_item(batch_id: str, item_id: str):
    try:
        return R.success(batch_task_service.delete_items(batch_id, [item_id]))
    except KeyError as exc:
        return R.error(msg=str(exc), code=404)
    except ValueError as exc:
        return R.error(msg=str(exc), code=400)


@router.post("/batch/tasks/{batch_id}/items/delete")
def delete_batch_items(batch_id: str, data: BatchDeleteItemsRequest):
    try:
        return R.success(batch_task_service.delete_items(batch_id, data.item_ids))
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
