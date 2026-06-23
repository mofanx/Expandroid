using System;
using System.Collections.Generic;
using System.Linq;
using Expandroid.Models;

namespace Expandroid.Services
{
    /// <summary>
    /// YAML structure-aware three-way merge at the trigger level.
    /// Compares Base (last sync snapshot) + Local (current) + Remote (pulled) and produces merged result.
    /// Uses whole-replacement strategy per trigger (no field-level merge).
    /// </summary>
    public class ThreeWayMergeResult
    {
        public Dictionary<string, Match> MergedDict { get; set; } = new();
        public List<Var> MergedGlobalVars { get; set; } = new();
        public List<string> Conflicts { get; set; } = new();
        public List<string> Warnings { get; set; } = new();
    }

    public class ThreeWayMergeService
    {
        private readonly SnapshotManager _snapshotManager;

        public ThreeWayMergeService(SnapshotManager snapshotManager)
        {
            _snapshotManager = snapshotManager;
        }

        /// <summary>
        /// Performs three-way merge of matches and global_vars.
        /// Base = last sync snapshot, Local = current local dict, Remote = pulled remote dict.
        /// </summary>
        public ThreeWayMergeResult Merge(
            Dictionary<string, Match> localDict,
            List<Var> localVars,
            Dictionary<string, Match> remoteDict,
            List<Var> remoteVars)
        {
            var result = new ThreeWayMergeResult();
            var baseSnapshot = _snapshotManager.GetSnapshot();
            var baseMatches = baseSnapshot?.Matches ?? new Dictionary<string, MatchSnapshot>();
            var baseVars = baseSnapshot?.GlobalVars ?? new List<VarSnapshot>();

            MergeMatches(localDict, remoteDict, baseMatches, result);
            MergeGlobalVars(localVars, remoteVars, baseVars, result);

            return result;
        }

        private void MergeMatches(
            Dictionary<string, Match> localDict,
            Dictionary<string, Match> remoteDict,
            Dictionary<string, MatchSnapshot> baseMatches,
            ThreeWayMergeResult result)
        {
            var allKeys = new HashSet<string>(
                localDict.Keys.Concat(remoteDict.Keys).Concat(baseMatches.Keys),
                StringComparer.OrdinalIgnoreCase);

            foreach (var key in allKeys)
            {
                var inLocal = localDict.TryGetValue(key, out var localMatch);
                var inRemote = remoteDict.TryGetValue(key, out var remoteMatch);
                var inBase = baseMatches.TryGetValue(key, out var baseSnap);

                if (inBase && !inLocal && !inRemote)
                {
                    // Both deleted → skip (deleted)
                    continue;
                }

                if (inBase && !inLocal && inRemote && !MatchChangedFromBase(baseSnap, remoteMatch))
                {
                    // Base exists, Local deleted, Remote unchanged → delete (skip)
                    continue;
                }

                if (inBase && inLocal && !inRemote && !MatchChangedFromBase(baseSnap, localMatch))
                {
                    // Base exists, Remote deleted, Local unchanged → delete (skip)
                    continue;
                }

                if (inBase && !inLocal && inRemote && MatchChangedFromBase(baseSnap, remoteMatch))
                {
                    // Local deleted, Remote modified → conflict, keep remote + warning
                    result.MergedDict[key] = remoteMatch;
                    result.Warnings.Add($"'{key}': deleted locally but modified remotely, kept remote version");
                    continue;
                }

                if (inBase && inLocal && !inRemote && MatchChangedFromBase(baseSnap, localMatch))
                {
                    // Remote deleted, Local modified → conflict, keep local + warning
                    result.MergedDict[key] = localMatch;
                    result.Warnings.Add($"'{key}': deleted remotely but modified locally, kept local version");
                    continue;
                }

                if (inBase && inLocal && inRemote)
                {
                    var localChanged = MatchChangedFromBase(baseSnap, localMatch);
                    var remoteChanged = MatchChangedFromBase(baseSnap, remoteMatch);

                    if (localChanged && remoteChanged)
                    {
                        // Both modified same trigger → whole replacement, keep remote + warning
                        result.MergedDict[key] = remoteMatch;
                        result.Conflicts.Add(key);
                        result.Warnings.Add($"'{key}': both sides modified, kept remote version (whole replacement)");
                    }
                    else if (localChanged)
                    {
                        result.MergedDict[key] = localMatch;
                    }
                    else if (remoteChanged)
                    {
                        result.MergedDict[key] = remoteMatch;
                    }
                    else
                    {
                        // Neither changed → keep either (they're the same)
                        result.MergedDict[key] = localMatch;
                    }
                    continue;
                }

                if (!inBase && inLocal && inRemote)
                {
                    // Both added same trigger → keep remote + warning
                    result.MergedDict[key] = remoteMatch;
                    result.Conflicts.Add(key);
                    result.Warnings.Add($"'{key}': both sides added same trigger, kept remote version");
                    continue;
                }

                if (!inBase && inLocal && !inRemote)
                {
                    // Local new addition → keep
                    result.MergedDict[key] = localMatch;
                    continue;
                }

                if (!inBase && !inLocal && inRemote)
                {
                    // Remote new addition → keep
                    result.MergedDict[key] = remoteMatch;
                    continue;
                }
            }
        }

        private void MergeGlobalVars(
            List<Var> localVars,
            List<Var> remoteVars,
            List<VarSnapshot> baseVars,
            ThreeWayMergeResult result)
        {
            var baseByName = baseVars.ToDictionary(v => v.Name, StringComparer.OrdinalIgnoreCase);
            var localByName = (localVars ?? new List<Var>()).ToDictionary(v => v.Name, StringComparer.OrdinalIgnoreCase);
            var remoteByName = (remoteVars ?? new List<Var>()).ToDictionary(v => v.Name, StringComparer.OrdinalIgnoreCase);

            var allNames = new HashSet<string>(
                localByName.Keys.Concat(remoteByName.Keys).Concat(baseByName.Keys),
                StringComparer.OrdinalIgnoreCase);

            foreach (var name in allNames)
            {
                var inLocal = localByName.TryGetValue(name, out var localVar);
                var inRemote = remoteByName.TryGetValue(name, out var remoteVar);
                var inBase = baseByName.TryGetValue(name, out var baseSnap);

                if (inBase && !inLocal && !inRemote)
                    continue;

                if (inBase && !inLocal && inRemote && !VarChangedFromBase(baseSnap, remoteVar))
                    continue;

                if (inBase && inLocal && !inRemote && !VarChangedFromBase(baseSnap, localVar))
                    continue;

                if (inBase && !inLocal && inRemote && VarChangedFromBase(baseSnap, remoteVar))
                {
                    result.MergedGlobalVars.Add(remoteVar);
                    result.Warnings.Add($"global_var '{name}': deleted locally but modified remotely, kept remote");
                    continue;
                }

                if (inBase && inLocal && !inRemote && VarChangedFromBase(baseSnap, localVar))
                {
                    result.MergedGlobalVars.Add(localVar);
                    result.Warnings.Add($"global_var '{name}': deleted remotely but modified locally, kept local");
                    continue;
                }

                if (inBase && inLocal && inRemote)
                {
                    var localChanged = VarChangedFromBase(baseSnap, localVar);
                    var remoteChanged = VarChangedFromBase(baseSnap, remoteVar);

                    if (localChanged && remoteChanged)
                    {
                        result.MergedGlobalVars.Add(remoteVar);
                        result.Conflicts.Add($"global_var:{name}");
                        result.Warnings.Add($"global_var '{name}': both sides modified, kept remote (whole replacement)");
                    }
                    else if (localChanged)
                    {
                        result.MergedGlobalVars.Add(localVar);
                    }
                    else if (remoteChanged)
                    {
                        result.MergedGlobalVars.Add(remoteVar);
                    }
                    else
                    {
                        result.MergedGlobalVars.Add(localVar);
                    }
                    continue;
                }

                if (!inBase && inLocal && inRemote)
                {
                    result.MergedGlobalVars.Add(remoteVar);
                    result.Conflicts.Add($"global_var:{name}");
                    result.Warnings.Add($"global_var '{name}': both sides added, kept remote");
                    continue;
                }

                if (inLocal)
                    result.MergedGlobalVars.Add(localVar);
                else if (inRemote)
                    result.MergedGlobalVars.Add(remoteVar);
            }
        }

        private static bool MatchChangedFromBase(MatchSnapshot baseSnap, Match current)
        {
            if (baseSnap == null || current == null) return true;
            return baseSnap.Replace != current.Replace
                || baseSnap.Form != current.Form
                || baseSnap.Word != current.Word
                || baseSnap.LeftWord != current.LeftWord
                || baseSnap.RightWord != current.RightWord
                || baseSnap.PropagateCase != current.PropagateCase
                || baseSnap.UppercaseStyle != current.UppercaseStyle
                || baseSnap.Regex != current.Regex
                || baseSnap.VarsCount != (current.Vars?.Count ?? 0)
                || !ListEquals(baseSnap.Triggers, current.Triggers);
        }

        private static bool VarChangedFromBase(VarSnapshot baseSnap, Var current)
        {
            if (baseSnap == null || current == null) return true;
            return baseSnap.Type != current.Type
                || baseSnap.Echo != current.Params?.Echo
                || baseSnap.Format != current.Params?.Format;
        }

        private static bool ListEquals(List<string> a, List<string> b)
        {
            if (a == null && b == null) return true;
            if (a == null || b == null) return false;
            if (a.Count != b.Count) return false;
            return a.SequenceEqual(b);
        }
    }
}
