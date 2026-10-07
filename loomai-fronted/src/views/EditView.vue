<script setup>
/**
 * 单张图片编辑页（学生演示项目）
 *
 * 提交走异步任务：提交成功后仅提示「请求已提交」并清空表单，结果到「编辑结果查询」页查看。
 */
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { submitEditTask, myImages } from '../services/api'
import ImageSelectModal from '../components/ImageSelectModal.vue'
import editDefaultImg from '../assets/image/edit-default.png'
import submitIcon from '../assets/image/submit.svg'
import deleteImageIcon from '../assets/image/delete-image.svg'

// ---------- 状态 ----------
/** 我的图片列表（从接口拉取，打开弹框时用） */
const myImagesList = ref([])
/** 当前选中的图片 URL，提交时传给编辑接口 */
const selectedUrl = ref('')
/** 选择图片弹框是否打开 */
const imageModalOpen = ref(false)
/** 用户输入的编辑指令 */
const editInstruction = ref('')
/** 是否正在提交任务 */
const isSubmitting = ref(false)

/** 从后台拉取「我的图片」列表，供弹框内选择 */
const fetchMyImagesList = async () => {
  try {
    const { data } = await myImages()
    myImagesList.value = data.data || []
  } catch (err) {
    ElMessage.error(err?.message || '加载图片失败')
  }
}

/** 打开选择图片弹框前先拉取最新列表，再打开弹框 */
const openImageModal = async () => {
  await fetchMyImagesList()
  imageModalOpen.value = true
}

/** 提交编辑：校验后提交异步任务，成功后提示并清空表单 */
const submitEdit = async () => {
  if (!selectedUrl.value) {
    ElMessage.warning('请先从"我的图片"选择一张图片')
    return
  }
  if (!editInstruction.value?.trim()) {
    ElMessage.warning('请输入编辑指令')
    return
  }
  try {
    isSubmitting.value = true
    await submitEditTask({
      image: selectedUrl.value,
      instruction: editInstruction.value,
    })
    ElMessage.success('请求已提交，可到「编辑结果查询」查看')
    selectedUrl.value = ''
    editInstruction.value = ''
  } catch (err) {
    ElMessage.error(err?.message || '请求失败')
  } finally {
    isSubmitting.value = false
  }
}
</script>

<template>
  <div class="card ui-card">
    <!-- 顶部展示区：默认占位图 -->
    <div class="edit-hero">
      <h2 class="page-title">单张图片编辑</h2>
      <img :src="editDefaultImg" alt="" class="edit-default-img" />
    </div>

    <!-- 底部表单：选择图片槽 + 编辑指令输入框 + 提交 -->
    <div class="edit-form-box">
      <div class="edit-form-top">
        <!-- 图片槽：未选显示「选择图片」按钮，已选显示缩略图与删除按钮 -->
        <div class="edit-image-slot">
          <template v-if="!selectedUrl">
            <button type="button" class="edit-select-btn" @click="openImageModal">
              <span class="edit-select-icon">+</span>
              <span class="edit-select-label">图片</span>
            </button>
          </template>
          <template v-else>
            <div class="edit-selected-wrap">
              <div class="edit-selected-img-wrap">
                <el-image
                  :src="selectedUrl"
                  fit="cover"
                  class="edit-selected-img"
                  :preview-src-list="[selectedUrl]"
                  preview-teleported
                />
              </div>
              <button
                v-if="!isSubmitting"
                type="button"
                class="edit-delete-btn"
                aria-label="删除图片"
                @click.stop="selectedUrl = ''"
              >
                <img :src="deleteImageIcon" alt="" class="edit-delete-icon" />
              </button>
            </div>
          </template>
        </div>
        <!-- 编辑指令输入框 -->
        <textarea
          class="edit-textarea"
          v-model="editInstruction"
          rows="3"
          placeholder="描述您想呈现的画面，例如：把人物的上衣换成粉色的"
          :disabled="isSubmitting"
        ></textarea>
      </div>
      <!-- 提交按钮（需已选图且已输入指令才可点，请求中显示「提交中…」） -->
      <div class="edit-form-actions">
        <button
          type="button"
          class="edit-submit-btn"
          :disabled="isSubmitting || !selectedUrl || !editInstruction.trim()"
          @click="submitEdit"
        >
          <img :src="submitIcon" alt="" class="edit-submit-icon" />
          {{ isSubmitting ? '提交中…' : '提交' }}
        </button>
      </div>
    </div>

    <!-- 选择图片弹框：单选，确认后把选中的 url 赋给 selectedUrl -->
    <ImageSelectModal
      v-model="imageModalOpen"
      :images="myImagesList"
      :selected="selectedUrl"
      mode="single"
      @confirm="(url) => (selectedUrl = url)"
    />
  </div>
</template>

<style scoped>
/* ---------- 卡片与标题 ---------- */
.card {
  padding: 20px;
  display: flex;
  flex-direction: column;
  align-items: center;
}
.page-title {
  text-align: center;
  width: 100%;
}
.edit-hero {
  margin-top: 16px;
}
.edit-default-img {
  width: 483px;
  height: 323px;
  display: block;
  object-fit: contain;
  border-radius: 12px;
}

/* ---------- 底部表单框 ---------- */
.edit-form-box {
  margin-top: 20px;
  padding: 20px;
  width: 710px;
  max-width: 100%;
  min-height: 207px;
  box-sizing: border-box;
  background: #FFFFFF;
  border: 1px solid #EAEAEA;
  border-radius: 12px;
  box-shadow: 0px 4px 10px 0px rgba(0, 0, 0, 0.05);
}
.edit-form-top {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

/* 选择图片槽：80x80 占位按钮 / 已选缩略图 + 删除按钮 */
.edit-image-slot {
  width: 80px;
  height: 80px;
  flex-shrink: 0;
}
.edit-selected-wrap {
  position: relative;
  width: 80px;
  height: 80px;
  overflow: visible;
  cursor: pointer;
}
.edit-selected-img-wrap {
  width: 100%;
  height: 100%;
  border-radius: 4px;
  overflow: hidden;
  background: #F5F7FD;
}
.edit-selected-img {
  width: 100%;
  height: 100%;
  display: block;
}
.edit-selected-img :deep(.el-image__inner) {
  width: 100% !important;
  height: 100% !important;
  object-fit: cover;
}
.edit-delete-btn {
  position: absolute;
  top: -8px;
  right: -8px;
  z-index: 2;
  width: 16px;
  height: 16px;
  padding: 0;
  border: none;
  background: rgba(0, 0, 0, 0.4);
  border-radius: 50%;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: background 0.2s;
}
.edit-delete-btn:hover {
  background: rgba(0, 0, 0, 0.55);
}
.edit-delete-icon {
  width: 16px;
  height: 16px;
  object-fit: contain;
}
.edit-select-btn {
  flex-shrink: 0;
  width: 80px;
  height: 80px;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 6px;
  background: #F5F7FD;
  border: none;
  border-radius: 4px;
  cursor: pointer;
  transition: opacity 0.2s;
  padding: 0;
}
.edit-select-btn:hover {
  opacity: 0.9;
}
.edit-select-icon {
  font-size: 24px;
  font-weight: 300;
  color: #9CA3AF;
  line-height: 1;
}
.edit-select-label {
  font-size: 13px;
  color: #9CA3AF;
}
.edit-textarea {
  width: 100%;
  min-width: 0;
  padding: 8px 0;
  border: none;
  border-radius: 0;
  outline: none;
  font-size: 14px;
  line-height: 1.5;
  resize: vertical;
  font-family: inherit;
  background: transparent;
  box-sizing: border-box;
}
.edit-textarea::placeholder {
  color: rgba(16, 24, 40, 0.4);
}
.edit-textarea:focus {
  box-shadow: none;
}
.edit-form-actions {
  margin-top: 16px;
  display: flex;
  justify-content: flex-end;
}
.edit-submit-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  width: 88px;
  height: 36px;
  padding: 0;
  background: linear-gradient(90deg, #884BFF 0%, #7530FE 100%);
  color: #fff;
  border: none;
  border-radius: 6px;
  font-size: 14px;
  font-weight: 500;
  cursor: pointer;
  transition: opacity 0.2s;
}
.edit-submit-btn:hover:not(:disabled) {
  opacity: 0.92;
}
.edit-submit-btn:disabled {
  opacity: 0.7;
  cursor: not-allowed;
}
.edit-submit-icon {
  width: 14px;
  height: 14px;
  display: block;
  object-fit: contain;
}
</style>
