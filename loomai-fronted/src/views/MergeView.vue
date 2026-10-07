<script setup>
/**
 * 合并两张图片页（学生演示项目）
 *
 * 提交走异步任务：提交成功后仅提示「请求已提交」并清空表单，结果到「合并结果查询」页查看。
 */
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { submitMergeTask, myImages } from '../services/api'
import ImageSelectModal from '../components/ImageSelectModal.vue'
import mergeDefaultImg from '../assets/image/merge-default.png'
import submitIcon from '../assets/image/submit.svg'
import deleteImageIcon from '../assets/image/delete-image.svg'

// ---------- 状态 ----------
const myImagesList = ref([])
const selectedUrl1 = ref('')
const selectedUrl2 = ref('')
const imageModalOpen = ref(false)
/** 当前打开弹框是为哪个槽选图：1 或 2 */
const imageModalSlot = ref(1)
/** 合并指令（用户输入的描述） */
const mergeInstruction = ref('')
/** 是否正在提交任务 */
const isSubmitting = ref(false)

/** 从后台获取「我的图片」列表 */
const fetchMyImagesList = async () => {
  try {
    const { data } = await myImages()
    myImagesList.value = data.data || []
  } catch (err) {
    ElMessage.error(err?.message || '加载图片失败')
  }
}

/** 为指定槽打开选择图片弹框（每次只选一张，仅影响该槽） */
const openImageModal = async (slot) => {
  imageModalSlot.value = slot
  await fetchMyImagesList()
  imageModalOpen.value = true
}

/** 弹框确认时，把选中的 url 写入当前槽 */
const onModalConfirm = (url) => {
  if (imageModalSlot.value === 1) selectedUrl1.value = url || ''
  else selectedUrl2.value = url || ''
}

/** 当前弹框对应的已选 url（用于传给 ImageSelectModal selected） */
const modalSelectedUrl = () =>
  imageModalSlot.value === 1 ? selectedUrl1.value : selectedUrl2.value

/** 提交合并请求 */
const submitMerge = async () => {
  if (!selectedUrl1.value || !selectedUrl2.value) {
    ElMessage.warning('请从"我的图片"选齐两张图片')
    return
  }
  if (selectedUrl1.value === selectedUrl2.value) {
    ElMessage.warning('两张图片不能相同')
    return
  }
  if (!mergeInstruction.value?.trim()) {
    ElMessage.warning('请输入合并指令')
    return
  }
  try {
    isSubmitting.value = true
    await submitMergeTask({
      image1: selectedUrl1.value,
      image2: selectedUrl2.value,
      instruction: mergeInstruction.value,
    })
    ElMessage.success('请求已提交，可到「合并结果查询」查看')
    selectedUrl1.value = ''
    selectedUrl2.value = ''
    mergeInstruction.value = ''
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
    <div class="merge-hero">
      <h2 class="page-title">合并2张图片</h2>
      <img :src="mergeDefaultImg" alt="" class="merge-default-img" />
    </div>

    <!-- 底部表单：图片1槽 + 图片2槽 + 指令输入 + 提交 -->
    <div class="merge-form-box">
      <div class="merge-form-top">
        <!-- 两个独立的图片槽，互不影响：每次只选一张，各自可单独删除 -->
        <div class="merge-slots">
          <div class="merge-image-slot">
            <template v-if="!selectedUrl1">
              <button type="button" class="merge-select-btn" @click="openImageModal(1)">
                <span class="merge-select-icon">+</span>
                <span class="merge-select-label">图片1</span>
              </button>
            </template>
            <template v-else>
              <div class="merge-selected-wrap">
                <div class="merge-selected-img-wrap">
                  <el-image
                    :src="selectedUrl1"
                    fit="cover"
                    class="merge-selected-img"
                    :preview-src-list="[selectedUrl1]"
                    preview-teleported
                  />
                </div>
                <span class="merge-slot-num">1</span>
                <button
                  v-if="!isSubmitting"
                  type="button"
                  class="merge-delete-btn"
                  aria-label="删除图片1"
                  @click.stop="selectedUrl1 = ''"
                >
                  <img :src="deleteImageIcon" alt="" class="merge-delete-icon" />
                </button>
              </div>
            </template>
          </div>
          <div class="merge-image-slot">
            <template v-if="!selectedUrl2">
              <button type="button" class="merge-select-btn" @click="openImageModal(2)">
                <span class="merge-select-icon">+</span>
                <span class="merge-select-label">图片2</span>
              </button>
            </template>
            <template v-else>
              <div class="merge-selected-wrap">
                <div class="merge-selected-img-wrap">
                  <el-image
                    :src="selectedUrl2"
                    fit="cover"
                    class="merge-selected-img"
                    :preview-src-list="[selectedUrl2]"
                    preview-teleported
                  />
                </div>
                <span class="merge-slot-num">2</span>
                <button
                  v-if="!isSubmitting"
                  type="button"
                  class="merge-delete-btn"
                  aria-label="删除图片2"
                  @click.stop="selectedUrl2 = ''"
                >
                  <img :src="deleteImageIcon" alt="" class="merge-delete-icon" />
                </button>
              </div>
            </template>
          </div>
        </div>
        <!-- 合并指令输入框，请求中禁用 -->
        <textarea
          :disabled="isSubmitting"
          class="merge-textarea"
          v-model="mergeInstruction"
          rows="3"
          placeholder="描述您想呈现的画面，例如：给图2的人物换上图1的衣服"
        ></textarea>
      </div>
      <!-- 提交按钮（需两张图且已输入指令才可点） -->
      <div class="merge-form-actions">
        <button
          type="button"
          class="merge-submit-btn"
          :disabled="isSubmitting || !selectedUrl1 || !selectedUrl2 || !mergeInstruction.trim()"
          @click="submitMerge"
        >
          <img :src="submitIcon" alt="" class="merge-submit-icon" />
          {{ isSubmitting ? '提交中…' : '提交' }}
        </button>
      </div>
    </div>

    <!-- 选择图片弹框：单选，仅作用于当前槽（imageModalSlot） -->
    <ImageSelectModal
      v-model="imageModalOpen"
      :images="myImagesList"
      :selected="modalSelectedUrl()"
      mode="single"
      @confirm="onModalConfirm"
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

/* ---------- 顶部展示区：默认图 ---------- */
.merge-hero {
  margin-top: 16px;
}
.merge-default-img {
  width: 729px;
  max-width: 100%;
  height: 323px;
  display: block;
  object-fit: contain;
  border-radius: 12px;
}

/* ---------- 底部表单框 ---------- */
.merge-form-box {
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
.merge-form-top {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

/* 两个图片槽并排，互不影响 */
.merge-slots {
  display: flex;
  gap: 12px;
  flex-wrap: wrap;
}
.merge-image-slot {
  width: 80px;
  height: 80px;
  flex-shrink: 0;
}
.merge-selected-wrap {
  position: relative;
  width: 80px;
  height: 80px;
  overflow: visible;
  cursor: pointer;
}
/* 图片左下角序号角标 */
.merge-slot-num {
  position: absolute;
  left: 0;
  bottom: 0;
  z-index: 2;
  min-width: 18px;
  height: 18px;
  padding: 0 6px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: rgba(0, 0, 0, 0.6);
  color: #fff;
  font-size: 12px;
  font-weight: 600;
  border-radius: 4px;
  pointer-events: none;
}
.merge-selected-img-wrap {
  width: 100%;
  height: 100%;
  border-radius: 4px;
  overflow: hidden;
  background: #F5F7FD;
}
.merge-selected-img {
  width: 100%;
  height: 100%;
  display: block;
}
.merge-selected-img :deep(.el-image__inner) {
  width: 100% !important;
  height: 100% !important;
  object-fit: cover;
}
.merge-delete-btn {
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
.merge-delete-btn:hover:not(:disabled) {
  background: rgba(0, 0, 0, 0.55);
}
.merge-delete-icon {
  width: 16px;
  height: 16px;
  object-fit: contain;
}
.merge-select-btn {
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
.merge-select-btn:hover {
  opacity: 0.9;
}
.merge-select-icon {
  font-size: 24px;
  font-weight: 300;
  color: #9CA3AF;
  line-height: 1;
}
.merge-select-label {
  font-size: 13px;
  color: #9CA3AF;
}

/* 指令输入框 */
.merge-textarea {
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
.merge-textarea::placeholder {
  color: rgba(16, 24, 40, 0.4);
}
.merge-textarea:focus {
  box-shadow: none;
}

/* 提交按钮区 */
.merge-form-actions {
  margin-top: 16px;
  display: flex;
  justify-content: flex-end;
}
.merge-submit-btn {
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
.merge-submit-btn:hover:not(:disabled) {
  opacity: 0.92;
}
.merge-submit-btn:disabled {
  opacity: 0.7;
  cursor: not-allowed;
}
.merge-submit-icon {
  width: 14px;
  height: 14px;
  display: block;
  object-fit: contain;
}
</style>
