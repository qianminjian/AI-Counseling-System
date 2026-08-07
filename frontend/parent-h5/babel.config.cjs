// doing/73：Taro 4 标准 babel 预设（type: module 工程用 .cjs 后缀）
module.exports = {
  presets: [
    ['taro', { framework: 'react', ts: true }],
  ],
}
