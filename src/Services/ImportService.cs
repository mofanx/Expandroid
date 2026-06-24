using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Text.Json;
using System.Threading.Tasks;
using CommunityToolkit.Mvvm.Messaging;
using Expandroid.Models;
using YamlDotNet.Serialization;
using YamlDotNet.Serialization.NamingConventions;

namespace Expandroid.Services
{
    public class ImportResult
    {
        public int Skips { get; set; }
        public List<string> SkipDetails { get; set; } = new();
        public List<Var> ImportedGlobalVars { get; set; }
        public bool Success { get; set; }
        public string Error { get; set; }
    }

    public class ImportService
    {
        private readonly YamlWorkspace _yamlWorkspace;
        private readonly ILocalizationService _localizationService;

        public ImportService(YamlWorkspace yamlWorkspace, ILocalizationService localizationService)
        {
            _yamlWorkspace = yamlWorkspace;
            _localizationService = localizationService;
        }

        public ImportResult ProcessImport(DictWrapper localDict, string baseDir, Dictionary<string, Match> dict, int depth = 0)
        {
            var result = new ImportResult();
            ProcessImportInternal(localDict, baseDir, dict, result, isRoot: true, depth);
            result.ImportedGlobalVars = localDict.Global_vars;
            result.Success = true;
            return result;
        }

        private void ProcessImportInternal(DictWrapper localDict, string baseDir, Dictionary<string, Match> dict, ImportResult result, bool isRoot, int depth)
        {
            if (depth > 5 || localDict?.Matches is null)
                return;

            foreach (var item in localDict.Matches)
            {
                if (item.Vars is not null)
                {
                    bool notSupported = item.Replace is null;
                    string skipReason = null;
                    foreach (var x in item.Vars)
                    {
                        if (x.Type is not null)
                        {
                            if (!AppSettings.SupportedList.Contains(x.Type))
                            {
                                result.Skips++;
                                notSupported = true;
                                skipReason = $"{item.Trigger ?? "?"}: unsupported var type '{x.Type}'";
                                break;
                            }
                            else if (x.Type == "date")
                            {
                                try
                                {
                                    x.Params.Format = Utils.GetTheRealFormat(x.Params.Format);
                                }
                                catch (Exception)
                                {
                                    throw new Exception(_localizationService.GetString("DateExtensionParameterFormatsError"));
                                }
                            }
                        }
                    }
                    if (notSupported)
                    {
                        if (skipReason is not null)
                            result.SkipDetails.Add(skipReason);
                        continue;
                    }
                }
                if (item.Triggers is not null && item.Triggers.Count > 0)
                {
                    foreach (var t in item.Triggers)
                    {
                        var clone = new Match(item) { Trigger = t };
                        dict[t] = clone;
                        WeakReferenceMessenger.Default.Send(new AcServiceMessage(("Add", clone)));
                    }
                }
                else if (!string.IsNullOrEmpty(item.Trigger))
                {
                    dict[item.Trigger] = item;
                    WeakReferenceMessenger.Default.Send(new AcServiceMessage(("Add", item)));
                }
                else if (!string.IsNullOrEmpty(item.Regex))
                {
                    dict[$"__regex_{item.Regex}"] = item;
                    WeakReferenceMessenger.Default.Send(new AcServiceMessage(("Add", item)));
                }
            }

            if (isRoot && localDict.Imports is not null && baseDir is not null)
            {
                foreach (var importPath in localDict.Imports)
                {
                    try
                    {
                        var fullPath = Path.Combine(baseDir, importPath);
                        if (File.Exists(fullPath))
                        {
                            var yamlText = File.ReadAllText(fullPath);
                            var deserializer = new DeserializerBuilder()
                                .WithNamingConvention(UnderscoredNamingConvention.Instance).IgnoreUnmatchedProperties()
                                .Build();
                            var imported = deserializer.Deserialize<DictWrapper>(yamlText);
                            ProcessImportInternal(imported, Path.GetDirectoryName(fullPath), dict, result, isRoot: false, depth + 1);
                        }
                    }
                    catch (Exception) { }
                }
            }
        }

        public async Task<ImportResult> ImportFromFolderAsync(string folderPath, Dictionary<string, Match> dict)
        {
            var result = new ImportResult();

            var importedDict = await _yamlWorkspace.ReadFromFolderAsync(folderPath);
            foreach (var item in importedDict)
            {
                if (item.Value.Vars is not null)
                {
                    bool notSupported = item.Value.Replace is null;
                    foreach (var x in item.Value.Vars)
                    {
                        if (x.Type is not null && !AppSettings.SupportedList.Contains(x.Type))
                        {
                            result.Skips++;
                            notSupported = true;
                            result.SkipDetails.Add($"{item.Key ?? "?"}: unsupported var type '{x.Type}'");
                            break;
                        }
                        else if (x.Type == "date")
                        {
                            try { x.Params.Format = Utils.GetTheRealFormat(x.Params.Format); }
                            catch (Exception) { }
                        }
                    }
                    if (notSupported) continue;
                }
                if (item.Value.Triggers is not null && item.Value.Triggers.Count > 0)
                {
                    foreach (var t in item.Value.Triggers)
                    {
                        var clone = new Match(item.Value) { Trigger = t };
                        dict[t] = clone;
                        WeakReferenceMessenger.Default.Send(new AcServiceMessage(("Add", clone)));
                    }
                }
                else
                {
                    dict[item.Key] = item.Value;
                    WeakReferenceMessenger.Default.Send(new AcServiceMessage(("Add", item.Value)));
                }
            }

            result.Success = true;
            return result;
        }

        public static async Task<DictWrapper> DeserializeJsonFileAsync(Stream stream)
        {
            return await JsonSerializer.DeserializeAsync<DictWrapper>(stream);
        }

        public static DictWrapper DeserializeYamlFile(Stream stream)
        {
            var deserializer = new DeserializerBuilder()
                .WithNamingConvention(UnderscoredNamingConvention.Instance).IgnoreUnmatchedProperties()
                .Build();
            using TextReader tr = new StreamReader(stream);
            return deserializer.Deserialize<DictWrapper>(tr);
        }
    }
}
