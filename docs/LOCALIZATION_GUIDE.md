# 多语言扩展指南

本文档说明如何为 Expandroid 项目添加新的语言支持。

## 当前支持的架构

### 资源文件位置
- 英文资源：`src/Resources/AppResources.resx`
- 中文资源：`src/Resources/AppResources.zh.resx`

### 本地化服务
- 接口：`src/Services/ILocalizationService.cs`
- 实现：`src/Services/LocalizationService.cs`
- UI组件：`src/Shared/LanguageSwitcher.razor`

## 添加新语言步骤

### 1. 创建资源文件

在 `src/Resources/` 目录下创建新的资源文件，命名格式为 `AppResources.[culture].resx`

例如添加日语支持：
```
src/Resources/AppResources.ja.resx
```

### 2. 翻译资源键

复制 `AppResources.resx` 中的所有键值对到新文件，并翻译成目标语言。

**重要**：
- 保持所有 `data name` 属性完全一致
- 只翻译 `value` 标签内的内容
- 确保 XML 格式正确

### 3. 更新语言列表

编辑 `src/Services/LocalizationService.cs`，在 `_availableLanguages` 列表中添加新语言：

```csharp
_availableLanguages = new List<LanguageInfo>
{
    new LanguageInfo { Code = "en", Name = "English", NativeName = "English" },
    new LanguageInfo { Code = "zh", Name = "Chinese", NativeName = "中文" },
    new LanguageInfo { Code = "ja", Name = "Japanese", NativeName = "日本語" } // 新增
};
```

### 4. 测试新语言

1. 重新编译项目
2. 运行应用
3. 在语言切换器中选择新语言
4. 验证所有界面文本正确显示

## 语言代码规范

使用标准的 ISO 639-1 语言代码：
- `en` - English
- `zh` - Chinese
- `ja` - Japanese
- `ko` - Korean
- `fr` - French
- `de` - German
- `es` - Spanish
- 等等

## 资源键命名规范

- 使用 PascalCase
- 名称应该描述性强
- 避免缩写
- 例如：`SaveButton` 而不是 `SaveBtn`

## 常见问题

### Q: 如何处理文本中的变量占位符？
A: 使用 `{0}`, `{1}` 等占位符，在代码中使用 `string.Format()` 填充

### Q: 如何添加新的资源键？
A: 在所有资源文件中添加相同的键，确保键名一致

### Q: 语言切换后界面没有更新？
A: 确保调用了 `StateHasChanged()` 并且订阅了 `OnLanguageChanged` 事件

### Q: 如何调试本地化问题？
A: 检查 ResourceManager 是否正确加载资源文件，确认 culture code 是否正确

## 自动化建议

为了便于维护，建议：

1. **定期检查遗漏**：使用 grep 搜索硬编码文本
   ```bash
   grep -r '"[A-Z][a-zA-Z\s]{5,}"' src/ --include="*.razor" --include="*.cs"
   ```

2. **资源键验证**：确保所有资源文件包含相同的键集合

3. **翻译完整性**：定期检查所有资源键是否都有对应的翻译

## 当前资源键统计

- 总资源键数量：60+
- 支持语言：2 (English, Chinese)
- 覆盖组件：Index.razor, Main.razor, LanguageSwitcher.razor

## 扩展优势

当前架构的优势：
- ✅ 标准化的 .NET ResourceManager
- ✅ 事件驱动的语言切换
- ✅ 语言偏好持久化
- ✅ 动态语言列表加载
- ✅ 零代码修改即可添加新语言（只需添加资源文件和更新语言列表）
