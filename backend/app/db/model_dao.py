from app.db.engine import get_db
from app.db.models.models import Model
from app.db.models.providers import Provider


def get_model_by_provider_and_name(provider_id: int, model_name: str):
    db = next(get_db())
    try:
        model = db.query(Model).filter_by(provider_id=provider_id, model_name=model_name).first()
        if model:
            return {
                "id": model.id,
                "provider_id": model.provider_id,
                "model_name": model.model_name,
                "created_at": model.created_at,
            }
        return None
    finally:
        db.close()


def insert_model(provider_id: int, model_name: str):
    db = next(get_db())
    try:
        model = Model(provider_id=provider_id, model_name=model_name)
        db.add(model)
        db.commit()
        db.refresh(model)
        return {
            "id": model.id,
            "provider_id": model.provider_id,
            "model_name": model.model_name,
            "created_at": model.created_at,
        }
    finally:
        db.close()


def get_models_by_provider(provider_id: int):
    db = next(get_db())
    try:
        models = db.query(Model).filter_by(provider_id=provider_id).all()
        return [{"id": m.id, "model_name": m.model_name} for m in models]
    finally:
        db.close()


def delete_model(model_id: int):
    db = next(get_db())
    try:
        model = db.query(Model).filter_by(id=model_id).first()
        if model:
            db.delete(model)
            db.commit()
    finally:
        db.close()


def get_all_models():
    db = next(get_db())
    try:
        # 配置管理接口：返回全部模型，口径与 get_all_providers() 保持一致。
        #
        # 之前这里用 inner join + filter(Provider.enabled == 1) 过滤掉「未启用供应商」的模型，
        # 但 /get_all_providers 返回的是全部供应商（含未启用的）。两个接口口径不一致，
        # 前端「选供应商 → 过滤该供应商的模型」时就出现：供应商能选中，模型列表却永远是空的，
        # 表现为下拉点选后没有任何选中态。可用性判断应交给调用方按 enabled 处理。
        #
        # 改用 outerjoin：供应商被删除后其模型仍可见（避免静默丢失），由管理界面负责清理。
        models = db.query(Model).outerjoin(Provider, Model.provider_id == Provider.id).all()
        return [
            {"id": m.id, "provider_id": m.provider_id, "model_name": m.model_name}
            for m in models
        ]
    finally:
        db.close()