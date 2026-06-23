using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Text.Json;
using System.Text.Json.Serialization;
using Expandroid.Models;
using Microsoft.Maui.Storage;

namespace Expandroid.Services
{
    /// <summary>
    /// Manages sync snapshots for three-way merge.
    /// A snapshot captures the state of local dict + global vars at the time of last successful sync.
    /// This serves as the "Base" in Base/Local/Remote three-way merge.
    /// </summary>
    public class SnapshotData
    {
        [JsonPropertyName("version")]
        public int Version { get; set; } = 1;

        [JsonPropertyName("createdAt")]
        public DateTime CreatedAt { get; set; }

        [JsonPropertyName("matches")]
        public Dictionary<string, MatchSnapshot> Matches { get; set; } = new();

        [JsonPropertyName("globalVars")]
        public List<VarSnapshot> GlobalVars { get; set; } = new();
    }

    public class MatchSnapshot
    {
        [JsonPropertyName("trigger")]
        public string Trigger { get; set; }

        [JsonPropertyName("triggers")]
        public List<string> Triggers { get; set; }

        [JsonPropertyName("replace")]
        public string Replace { get; set; }

        [JsonPropertyName("form")]
        public string Form { get; set; }

        [JsonPropertyName("word")]
        public bool Word { get; set; }

        [JsonPropertyName("leftWord")]
        public bool LeftWord { get; set; }

        [JsonPropertyName("rightWord")]
        public bool RightWord { get; set; }

        [JsonPropertyName("propagateCase")]
        public bool PropagateCase { get; set; }

        [JsonPropertyName("uppercaseStyle")]
        public string UppercaseStyle { get; set; }

        [JsonPropertyName("regex")]
        public string Regex { get; set; }

        [JsonPropertyName("varsCount")]
        public int VarsCount { get; set; }
    }

    public class VarSnapshot
    {
        [JsonPropertyName("name")]
        public string Name { get; set; }

        [JsonPropertyName("type")]
        public string Type { get; set; }

        [JsonPropertyName("echo")]
        public string Echo { get; set; }

        [JsonPropertyName("format")]
        public string Format { get; set; }
    }

    public class SnapshotManager
    {
        private readonly string _snapshotPath;
        private SnapshotData _current;

        public SnapshotData Current => _current;

        public SnapshotManager()
        {
            _snapshotPath = Path.Combine(FileSystem.Current.AppDataDirectory, "sync_snapshot.json");
            _current = LoadSnapshot();
        }

        /// <summary>
        /// Creates a snapshot from the current local state (after a successful sync).
        /// This becomes the "Base" for the next three-way merge.
        /// </summary>
        public void CreateSnapshot(Dictionary<string, Match> dict, List<Var> globalVars)
        {
            _current = new SnapshotData
            {
                CreatedAt = DateTime.UtcNow,
                Matches = new Dictionary<string, MatchSnapshot>(),
                GlobalVars = new List<VarSnapshot>()
            };

            foreach (var kv in dict)
            {
                var m = kv.Value;
                _current.Matches[kv.Key] = new MatchSnapshot
                {
                    Trigger = m.Trigger,
                    Triggers = m.Triggers?.ToList(),
                    Replace = m.Replace,
                    Form = m.Form,
                    Word = m.Word,
                    LeftWord = m.LeftWord,
                    RightWord = m.RightWord,
                    PropagateCase = m.PropagateCase,
                    UppercaseStyle = m.UppercaseStyle,
                    Regex = m.Regex,
                    VarsCount = m.Vars?.Count ?? 0
                };
            }

            if (globalVars != null)
            {
                foreach (var v in globalVars)
                {
                    _current.GlobalVars.Add(new VarSnapshot
                    {
                        Name = v.Name,
                        Type = v.Type,
                        Echo = v.Params?.Echo,
                        Format = v.Params?.Format
                    });
                }
            }

            SaveSnapshot();
        }

        public SnapshotData GetSnapshot() => _current;

        public bool HasSnapshot() => _current != null && _current.Matches.Count > 0;

        public void ClearSnapshot()
        {
            _current = new SnapshotData();
            try { File.Delete(_snapshotPath); } catch { }
        }

        private SnapshotData LoadSnapshot()
        {
            try
            {
                if (File.Exists(_snapshotPath))
                {
                    var json = File.ReadAllText(_snapshotPath);
                    return JsonSerializer.Deserialize<SnapshotData>(json) ?? new SnapshotData();
                }
            }
            catch { }
            return new SnapshotData();
        }

        private void SaveSnapshot()
        {
            try
            {
                var json = JsonSerializer.Serialize(_current, new JsonSerializerOptions
                {
                    WriteIndented = true,
                    DefaultIgnoreCondition = JsonIgnoreCondition.WhenWritingNull
                });
                File.WriteAllText(_snapshotPath, json);
            }
            catch { }
        }
    }
}
