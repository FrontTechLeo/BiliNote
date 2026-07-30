from fastapi import FastAPI



def create_app(lifespan) -> FastAPI:
    # Keep package imports lightweight.  Workers and unit tests can use a
    # downloader-independent service without importing every optional media
    # integration at module-import time.
    from .routers import batch_task, note, provider, model, config, chat

    app = FastAPI(title="BiliNote",lifespan=lifespan)
    app.include_router(note.router, prefix="/api")
    app.include_router(provider.router, prefix="/api")
    app.include_router(model.router,prefix="/api")
    app.include_router(config.router,  prefix="/api")
    app.include_router(chat.router, prefix="/api")
    app.include_router(batch_task.router, prefix="/api")

    return app
