import base64
import json
import os
import tempfile
import uuid
import threading
import re
import difflib

from PIL import Image
import requests
import redis
import torch
from transformers import CLIPModel, CLIPProcessor
from dashscope import Generation
from dashscope.aigc import MultiModalConversation
from deepagents import create_deep_agent
from flask import Flask, request, jsonify
from io import BytesIO

from flask.cli import load_dotenv
from langchain_community.chat_models.tongyi import ChatTongyi
from langchain_core.messages.human import HumanMessage
from langchain_core.prompts.chat import ChatPromptTemplate
from langchain_core.tools import tool

# 创建Flask应用实例
app = Flask(__name__)

# 获取配置信息
load_dotenv()

API_KEY = os.getenv("DASHSCOPE_API_KEY")

# 修复图片编辑/合并报 "No api key provided"：dashscope 在文件顶部 import 时（第 15 行）就已读取环境变量
# 并缓存 api_key，当时 .env 尚未 load_dotenv，导致 dashscope.api_key 为 None。而 MultiModalConversation.call
# （编辑/合并）未显式传 api_key，走 get_default_api_key() 会读到 None 报错。这里在 load_dotenv 后把 key 回填。
import dashscope
dashscope.api_key = API_KEY

# Redis 配置（与 Spring data.redis 对齐；可用环境变量覆盖）
REDIS_HOST = os.getenv("REDIS_HOST", "127.0.0.1")
REDIS_PORT = int(os.getenv("REDIS_PORT", "6379"))
REDIS_DB = int(os.getenv("REDIS_DB", "0"))
REDIS_TIMEOUT_SECS = int(os.getenv("REDIS_TIMEOUT_MS", "2000")) / 1000.0  # 默认 2000ms

_redis_client = redis.Redis(
    host=REDIS_HOST,
    port=REDIS_PORT,
    db=REDIS_DB,
    socket_timeout=REDIS_TIMEOUT_SECS,
    socket_connect_timeout=REDIS_TIMEOUT_SECS,
    decode_responses=True,
)

CLIP_MODEL_DIR = os.getenv(
    "CLIP_MODEL_DIR",
    r"D:\Learn\modelscope\hub\models\openai-mirror\clip-vit-base-patch16",
)
_clip_model = None
_clip_processor = None
_clip_lock = threading.Lock()


def get_clip_model_and_processor():
    """
    延迟加载本地 CLIP 模型，避免每次请求重复加载。
    该模型的 image embedding 维度通常为 512。
    """
    global _clip_model, _clip_processor
    if _clip_model is not None and _clip_processor is not None:
        return _clip_model, _clip_processor

    # 简单单例锁，确保并发下只加载一次
    with _clip_lock:
        if _clip_model is None or _clip_processor is None:
            _clip_processor = CLIPProcessor.from_pretrained(CLIP_MODEL_DIR)
            _clip_model = CLIPModel.from_pretrained(CLIP_MODEL_DIR)
            _clip_model.eval()

    return _clip_model, _clip_processor

# 将图片bytes转换成base_uri
def process_image(image_data : bytes) -> str:
    img = Image.open(BytesIO(image_data))
    image_format = (img.format).lower()
    image_base64 = base64.b64encode(image_data).decode("utf-8")
    data_uri = f"data:{image_format};base64,{image_base64}"
    return data_uri

# 调用大模型生成图片文字描述信息
def describe_image(image_data : bytes) -> str:
    try:
        # 1. 先把图片转化成base64
        data_uri = process_image(image_data)
        # 2. 构建LangChain的请求
        human_content = [
            {"image": data_uri},
            {
                "text": (
                    "用一句话简要地概括这张图片的内容，"
                    "并给出3到5个关键词（使用逗号分隔开），不要过多地解释"
                )
            },
        ]
        # 3. 构建访问大模型的实例
        vl_llm = ChatTongyi(
            model_name="qwen-vl-max",
            temperature=0.0,
            dashscope_api_key=API_KEY
        )
        # 4. 把访问大模型得到的结果进行处理返回
        resp = vl_llm.invoke([HumanMessage(content=human_content)])
        return resp.content[0]['text']
    except Exception as e :
        print(f"生成图片的文字描述信息出现异常:{e}")
        return ""


# 对图片的描述信息做最终的判定
def validate_image(image_desc : str) -> bool:
    try:
        prompt = ChatPromptTemplate.from_messages(
            [
                (
                    "system",
                    "你是一个图片审核助手，当前的业务只允许两种图片："
                    "1) 衣服/服装/穿搭相关，只要图片中有人物/衣服就算合格;"
                    "2) 人物人像(人脸照、半身照、全身照).\n"
                    "需要你来判断当前图片的内容是否是以上两类图片，如果是输出是，如果否输出否。"
                    "请严格只输出是或者否，不要别的内容"
                ),
                (
                    "human", f"图片文字描述: {image_desc}"
                )
            ]
        )

        llm = ChatTongyi(
            model_name="qwen-plus",
            temperature=0.7,
            dashscope_api_key=API_KEY
        )

        resp = llm.invoke(prompt.format_messages())
        text = resp.content
        return text.startswith("是")
    except Exception as e:
        print(f"做图片内容判定的时候出现异常:{e}")
        return False


def clip_image_to_512d(image_data: bytes) -> list:
    """
    把上传图片转成 CLIP 512 维向量（float 列表）。

    归一化后的向量更适合做余弦相似度检索。
    """
    model, processor = get_clip_model_and_processor()

    img = Image.open(BytesIO(image_data)).convert("RGB")
    inputs = processor(images=img, return_tensors="pt")

    with torch.no_grad():
        # 当前 transformers 版本下，get_image_features 返回 BaseModelOutputWithPooling
        # 其中向量通常在 pooler_output 字段里。
        out = model.get_image_features(**inputs)
        if hasattr(out, "pooler_output") and out.pooler_output is not None:
            image_features = out.pooler_output
        elif hasattr(out, "image_embeds") and out.image_embeds is not None:
            image_features = out.image_embeds
        else:
            image_features = out
        image_features = image_features / image_features.norm(dim=-1, keepdim=True)

    vec = image_features[0].detach().cpu().tolist()
    if len(vec) != 512:
        raise ValueError(f"CLIP embedding 维度期望为 512，实际为 {len(vec)}")

    # 降低 Redis 里 JSON 体积，检索时精度通常足够
    vec = [round(float(x), 6) for x in vec]
    return vec


def cosine_similarity_512(a: list, b: list) -> float:
    """
    计算两个 512 维向量的余弦相似度。
    约定：向量已经做过 L2 归一化时，余弦相似度可近似为点积。
    """
    if a is None or b is None:
        return 0.0
    if len(a) != 512 or len(b) != 512:
        return 0.0
    try:
        s = 0.0
        for i in range(512):
            s += float(a[i]) * float(b[i])
        return float(s)
    except Exception:
        return 0.0


def _normalize_text_for_similarity(s: str) -> str:
    if s is None:
        return ""
    s = str(s).strip().lower()
    if not s:
        return ""
    # 把各种空白压缩成单空格；移除大多数标点符号（保留中英文数字与空白）
    s = re.sub(r"\s+", " ", s)
    s = re.sub(r"[^\w\u4e00-\u9fff\s]", " ", s)
    s = re.sub(r"\s+", " ", s).strip()
    return s


def _char_ngrams(s: str, n: int = 2) -> set:
    if not s:
        return set()
    if len(s) <= n:
        return {s}
    return {s[i : i + n] for i in range(0, len(s) - n + 1)}


def text_similarity(query: str, description: str) -> float:
    """
    文本相似度（不使用向量/模型）：
    - 使用 difflib 的序列相似度（对中英文都能工作）
    - 叠加 2-gram 字符集合的 Jaccard，相对更适合中文短句/关键词
    返回 [0,1]，越大越相似。
    """
    q = _normalize_text_for_similarity(query)
    d = _normalize_text_for_similarity(description)
    if not q or not d:
        return 0.0

    seq = difflib.SequenceMatcher(None, q, d).ratio()
    q2 = _char_ngrams(q, 2)
    d2 = _char_ngrams(d, 2)
    if not q2 or not d2:
        jac = 0.0
    else:
        jac = len(q2 & d2) / max(1, len(q2 | d2))

    # 取更“乐观”的相似度，以满足关键词命中场景
    return float(max(seq, jac))


# 定义审核图片的接口路由
@app.route("/api/validate-image", methods = ["POST"])
def validate_image_api():
    try:
        file = request.files["file"]
        image_data = file.read()
        desc = describe_image(image_data) # 调用大模型生成图片文字描述信息
        allow = validate_image(desc)     # 二次调用大模型，处理文字描述信息判断当前图片是否符合要求
        print(f"AI 对图片的描述是: {desc}, 最终判定结果是: {allow}")
        return jsonify({"code":200, "allow": allow}), 200
    except Exception as e:
        print(f"执行审核图片操作捕获异常:{e}")
        return jsonify({"code":500, "allow": False}), 500


def _get_field_from_request(name: str, default=None):
    """兼容 JSON body 与 form body 的字段读取。"""
    # JSON body（不强依赖 Content-Type，避免前端没设置导致读取失败）
    body = request.get_json(silent=True) or {}
    if name in body:
        return body.get(name, default)

    # form-data / x-www-form-urlencoded
    if name in request.form:
        return request.form.get(name, default)

    # query string
    if name in request.args:
        return request.args.get(name, default)

    return default


def _extract_user_id():
    user_id = _get_field_from_request("userId")
    if user_id is None:
        user_id = _get_field_from_request("user_id")
    if user_id is None:
        raise ValueError("userId 不能为空")

    # 允许前端传字符串数字
    try:
        return int(user_id)
    except Exception:
        return str(user_id)


def _extract_oss_url():
    for key in [
        "ossUrl",
        "oss_url",
        "oss_address",
        "ossAddress",
        "imageOssUrl",
        "image_oss_url",
    ]:
        v = _get_field_from_request(key)
        if v:
            return v

    # 兜底：有些前端把字段叫 url
    v = _get_field_from_request("url")
    return v


# ai处理上传的图片 并存储生成的描述
@app.route("/api/upload-image", methods=["POST"])
def upload_image_api():
    """
    接收参数:
    - ossUrl / oss_address: 图片 OSS 地址（已可访问的 http(s) URL）
    - userId: 用户 ID

    处理流程:
    1) 下载 OSS 图片 -> bytes
    2) 调用 qwen-vl-max 生成图片描述
    3) 调用本地 CLIP 生成 512 维向量
    4) 把 {userId, description, embedding} 写入 Redis（JSON 格式）
    """
    try:
        user_id = _extract_user_id()
        oss_url = _extract_oss_url()
        if not oss_url:
            return jsonify({"success": False, "error": "ossUrl/oss_address 不能为空"}), 400

        # 下载图片（OSS 通常是可直接 GET 的公开/内网 URL）
        resp = requests.get(oss_url, timeout=20)
        if resp.status_code != 200:
            return (
                jsonify(
                    {
                        "success": False,
                        "error": f"下载图片失败，status={resp.status_code}",
                    }
                ),
                400,
            )

        image_data = resp.content
        if not image_data:
            return jsonify({"success": False, "error": "下载到的图片为空"}), 400

        # 1) qwen-vl-max: 描述
        description = describe_image(image_data)
        if not description:
            return jsonify({"success": False, "error": "生成图片描述失败"}), 500

        # 2) CLIP: 向量（512维）
        embedding = clip_image_to_512d(image_data)

        # 3) 写入 Redis
        image_id = uuid.uuid4().hex
        payload = {
            "userId": user_id,
            "imageId": image_id,
            "ossUrl": oss_url,
            "description": description,
            "embedding512": embedding,
        }

        redis_key = f"clip:image:{image_id}"
        _redis_client.set(redis_key, json.dumps(payload, ensure_ascii=False))
        # 同时维护 user 的索引集合，后续你按 userId 扫描会更快（避免 keys * 全库扫描）
        _redis_client.sadd(f"clip:user:{user_id}:image_ids", image_id)

        return (
            jsonify(
                {
                    "success": True,
                    "imageId": image_id,
                    "description": description,
                    "embeddingDim": 512,
                }
            ),
            200,
        )
    except Exception as e:
        print(f"/api/upload-image 执行失败: {e}")
        return jsonify({"success": False, "error": str(e)}), 500

# 图片搜索(文搜图 图搜图)
@app.route("/api/search-image", methods=["POST"])
def search_image_api():
    """
    POST /api/search-image

    multipart/form-data:
    - userId: 必填
    - file: 可选（图搜图）
    - query: 可选（文搜图）

    逻辑：
    1) 读取 Redis: clip:user:{userId}:image_ids
    2) 再读取每个 clip:image:{imageId} 的缓存（包含 description, embedding512, ossUrl）
    3) 文搜图：query 与 description 做相似度，>0.5 按相似度倒序
    4) 图搜图：file 抽取 CLIP 向量，与 embedding512 做余弦相似度，>0.7 按相似度倒序
    """
    try:
        user_id = _extract_user_id()
        query = _get_field_from_request("query")
        file_storage = request.files.get("file")

        is_image_search = file_storage is not None and getattr(file_storage, "filename", "") != ""
        is_text_search = query is not None and str(query).strip() != ""

        if not is_image_search and not is_text_search:
            return jsonify({"code": 400, "message": "file 或 query 至少需要一个", "data": []}), 400

        # 1) 读取用户图片ID集合
        user_key = f"clip:user:{user_id}:image_ids"
        image_ids = list(_redis_client.smembers(user_key) or [])
        if not image_ids:
            return jsonify({"code": 200, "message": "查询成功", "data": []}), 200

        # 2) 拉取缓存内容
        items = []
        for image_id in image_ids:
            try:
                raw = _redis_client.get(f"clip:image:{image_id}")
                if not raw:
                    continue
                obj = json.loads(raw)
                items.append(obj)
            except Exception:
                continue

        if not items:
            return jsonify({"code": 200, "message": "查询成功", "data": []}), 200

        results = []

        if is_image_search:
            image_data = file_storage.read()
            if not image_data:
                return jsonify({"code": 400, "message": "上传图片为空", "data": []}), 400

            query_vec = clip_image_to_512d(image_data)
            for obj in items:
                emb = obj.get("embedding512")
                if not isinstance(emb, list) or len(emb) != 512:
                    continue
                sim = cosine_similarity_512(query_vec, emb)
                if sim >= 0.7:
                    results.append(
                        {
                            "filePath": obj.get("ossUrl") or obj.get("filePath") or "",
                            "similarity": round(float(sim), 4),
                        }
                    )

        else:
            for obj in items:
                desc = obj.get("description")
                if not desc:
                    continue
                sim = text_similarity(str(query), str(desc))
                print(sim)
                if sim >= 0.01:
                    results.append(
                        {
                            "filePath": obj.get("ossUrl") or obj.get("filePath") or "",
                            "similarity": round(float(sim), 4),
                        }
                    )

        results.sort(key=lambda x: x.get("similarity", 0.0), reverse=True)
        return jsonify({"code": 200, "message": "查询成功", "data": results}), 200
    except Exception as e:
        print(f"/api/search-image 执行失败: {e}")
        return jsonify({"code": 500, "message": "查询失败", "data": []}), 500

# 全局的agent智能体
deep_agent = None

llm = ChatTongyi(
    model_name="qwen-plus",
    temperature=0.1,
    dashscope_api_key = API_KEY
)

@tool
def edit_image_tool(image_path: str, instruction: str) -> str:
    """编辑单张图片"
    输入本地图片地址路径与编辑指令   调用Dashscope的图像编辑模型生成新的URL
    返回JSON字符串: {"success": true, "url":"..."} 或 {"success": false, "error":"..."}
    """
    try:
        with open(image_path, "rb") as f:
            image_data = f.read()
        image_data_uri = process_image(image_data)

        messages = [
            {
                "role": "user",
                "content": [
                    {
                        "image": image_data_uri
                    },
                    {
                        "text": instruction
                    }
                ]
            }
        ]

        # 构建请求大模型的参数
        params = {
            "model": "qwen-image-edit-plus",
            "messages": messages
        }

        # 调用Dashscope编辑模型
        response = MultiModalConversation.call(**params)

        url = response['output']['choices'][0]['message']['content'][0]['image']
        return json.dumps({"success": True, "url": url}, ensure_ascii=False)
    except Exception as e:
        print(f"调用编辑模型报错:{e}")
        return json.dumps({"success": False, "error": f"{e}"}, ensure_ascii=False)


@tool
def merge_image_tool(image_path1: str, image_path2: str, instruction: str) -> str:
    """合并两张图片"
    输入两张本地图片地址路径与合并指令，调用Dashscope的图像编辑模型生成新的URL
    返回JSON字符串: {"success": true, "url":"..."} 或 {"success": false, "error":"..."}
    """
    try:
        with open(image_path1, "rb") as f:
            image_data1 = f.read()
        with open(image_path2, "rb") as f:
            image_data2 = f.read()

        image_data_uri1 = process_image(image_data1)
        image_data_uri2 = process_image(image_data2)

        # 多图输入：把两张图都放进同一个 user content 里
        messages = [
            {
                "role": "user",
                "content": [
                    {"image": image_data_uri1},
                    {"image": image_data_uri2},
                    {"text": instruction},
                ],
            }
        ]

        params = {
            "model": "qwen-image-edit-plus",
            "messages": messages,
        }

        response = MultiModalConversation.call(**params)
        url = response["output"]["choices"][0]["message"]["content"][0]["image"]
        return json.dumps({"success": True, "url": url}, ensure_ascii=False)
    except Exception as e:
        print(f"调用合并模型报错:{e}")
        return json.dumps({"success": False, "error": f"{e}"}, ensure_ascii=False)


# 声明一下工具
skills_tools = [edit_image_tool, merge_image_tool]

# 创建agent
try:
    deep_agent = create_deep_agent(
        model=llm,
        tools=skills_tools,
        skills=["/skills/"],
        system_prompt=(
            "你是一个智能图片处理助手，你可以调用一个工具：\n"
            "- edit_image_tool：编辑单张图片 \n"
            "- merge_image_tool：合并两张图片 \n"
            "最终的输出格式JSON: {\"success\":true, \"url\":\"字符串类型\"} 或者 {\"success\":false, \"error\":\"字符串类型\"} "
        )
    )
    print("Agent 已经启用")
except Exception as e:
    print("Agent 未启用")
    deep_agent = None

# agent执行函数
def invoke_agent(param : str) -> dict:
    try:
        state = deep_agent.invoke(
            {
                "messages": [
                    {
                        "role": "user",
                        "content": param
                    }
                ]
            }
        )

        msg = (state or {}).get("messages") or []
        last = msg[-1] if msg else None
        content = last.content
        obj = json.loads(content)
        return obj
    except Exception as e:
        print(f"执行agent函数出现异常{e}")
        return {"success": False, "error": f"执行agent函数失败{e}"}

# 操作图片的函数
def skill_image() -> str:
    try:
        if deep_agent is None:
            return ""
        instruction = request.form.get("instruction")
        if not instruction:
            return ""

        tmp_dir = tempfile.gettempdir()
        tmp_paths = []

        prompt_lines = [
            "你必须调用一个工具，并且只输出 JSON格式",
            f"instruction: {instruction}"
        ]

        # 兼容旧接口：单图编辑仍然使用 file 字段
        # 新增能力：如果传 file1 + file2，则走合并路径
        if "file1" in request.files and "file2" in request.files:
            data1 = request.files["file1"].read()
            data2 = request.files["file2"].read()
            p1 = os.path.join(tmp_dir, f"aiwear_{uuid.uuid4().hex}_1.bin")
            p2 = os.path.join(tmp_dir, f"aiwear_{uuid.uuid4().hex}_2.bin")
            with open(p1, "wb") as f:
                f.write(data1)
            with open(p2, "wb") as f:
                f.write(data2)
            tmp_paths.extend([p1, p2])
            prompt_lines.append(f"image_path1: {p1}")
            prompt_lines.append(f"image_path2: {p2}")
        else:
            data = request.files["file"].read()
            p = os.path.join(tmp_dir, f"aiwear_{uuid.uuid4().hex}.bin")
            with open(p, "wb") as f:
                f.write(data)
            tmp_paths.append(p)
            prompt_lines.append(f"image_path: {p}")
        out = invoke_agent("\n".join(prompt_lines))
        return out.get("url", "")
    except Exception as e:
        print(f"执行skill异常{e}")
        return ""

# 定义操作图片（编辑图片+合并图片）的接口路由
@app.route("/api/skill/image", methods = ["POST"])
def skill_image_api():
    try:
        out = skill_image()
        return jsonify(
            {
                "success": True,
                "url": out,
            }
        ), 200
    except Exception as e:
        print(f"调用skill处理失败{e}")
        return jsonify(
            {
                "success": False,
                "error": str(e)
            }
        ), 500


# ============ Flash 实时对话（新增，SSE 流式） ============
@app.route("/api/chat", methods=["POST"])
def chat_api():
    # 延迟 import：只在调用本接口时才加载 rag/memory，避免影响现有接口
    from flask import Response, stream_with_context
    from rag import retrieve, retrieve_advanced
    from memory import ChatMemoryStore

    data = request.get_json(silent=True) or {}
    message = data.get("message", "")
    session_id = data.get("sessionId", "") or str(uuid.uuid4())
    profile = data.get("profile") or {}

    if not message:
        return jsonify({"success": False, "error": "message 不能为空"}), 400

    # 1. 短期记忆
    memory_store = ChatMemoryStore(_redis_client, llm)
    memory = memory_store.load_memory(session_id)
    history = memory_store.get_history_text(memory)

    # 2. RAG 检索相关知识
    knowledge_text = ""
    try:
        knowledge = retrieve_advanced(message, history, top_k=5)
        knowledge_text = "\n".join(f"- {k}" for k in knowledge if k)
    except Exception as e:
        print(f"RAG 检索失败: {e}")

    # 3. 用户画像
    style = profile.get("style") or ""
    color = profile.get("color") or ""
    body_type = profile.get("bodyType") or ""
    profile_text = ""
    if style or color or body_type:
        profile_text = (
            f"用户偏好：风格={style or '未知'}，颜色={color or '未知'}，体型={body_type or '未知'}。"
            "请尽量结合这些偏好给出建议。"
        )

    # 4. 组装系统提示
    system_prompt = (
        "你是「Flash」，一位专业的服装搭配助手。"
        "请用自然亲切的语气，给出具体、可执行的穿搭建议。"
        "只做文字交流，不涉及图片。回答简洁，避免过度展开。"
    )
    context_parts = [system_prompt]
    if profile_text:
        context_parts.append(profile_text)
    if knowledge_text:
        context_parts.append(f"可参考的搭配知识：\n{knowledge_text}")
    if history:
        context_parts.append(f"之前的对话摘要：{history}")
    system_content = "\n\n".join(context_parts)

    # 5. 流式生成
    # 注：langchain_community 的 ChatTongyi.stream 会把增量 token 吞掉、只吐一个完整文本 chunk，
    #     这里绕过它，直接调 DashScope Generation 的流式接口，逐 token 输出。
    def generate():
        ai_parts = []
        try:
            dashscope_messages = [
                {"role": "system", "content": system_content},
                {"role": "user", "content": message},
            ]
            responses = Generation.call(
                model="qwen-plus",
                messages=dashscope_messages,
                result_format="message",
                temperature=0.1,
                stream=True,
                incremental_output=True,
                api_key=API_KEY,
            )
            for resp in responses:
                choices = resp.get("output", {}).get("choices") or []
                if not choices:
                    continue
                token = choices[0].get("message", {}).get("content", "") or ""
                if token:
                    ai_parts.append(token)
                    yield f"data: {json.dumps({'delta': token}, ensure_ascii=False)}\n\n"
            ai_text = "".join(ai_parts)
            try:
                memory_store.save_turn(session_id, memory, message, ai_text)
            except Exception as e:
                print(f"保存短期记忆失败: {e}")
            yield "data: [DONE]\n\n"
        except Exception as e:
            print(f"Flash 流式生成异常: {e}")
            yield f"data: {json.dumps({'error': str(e)}, ensure_ascii=False)}\n\n"
            yield "data: [DONE]\n\n"

    return Response(stream_with_context(generate()), mimetype="text/event-stream")


# 服务启动函数
if __name__ == "__main__":
    print("AI服务启动成功！")
    app.run(debug=True, use_reloader=False, host="0.0.0.0", port=5000)