@echo off
cd /d "%~dp0"

start "Backend" powershell -NoExit -Command "cd backend; conda activate biliNote; python main.py"
start "Frontend" powershell -NoExit -Command "cd BillNote_frontend; npm run dev"

start http://localhost:3015/