using System;
using System.Collections.Generic;
using System.Globalization;
using System.Text.RegularExpressions;

namespace Expandroid.Models
{
    public static class Utils
    {
        private static readonly Dictionary<string, string> ChronoToDotNet = new()
        {
            { "%Y", "yyyy" },
            { "%y", "yy" },
            { "%m", "MM" },
            { "%b", "MMM" },
            { "%B", "MMMM" },
            { "%h", "MMM" },
            { "%d", "dd" },
            { "%e", "d" },
            { "%a", "ddd" },
            { "%A", "dddd" },
            { "%j", "DayOfYearPlaceholder" },
            { "%w", "DayOfWeekNumPlaceholder" },
            { "%u", "IsoDayOfWeekPlaceholder" },
            { "%D", "MM/dd/yyyy" },
            { "%F", "yyyy/MM/dd" },
            { "%H", "HH" },
            { "%I", "hh" },
            { "%p", "tt" },
            { "%M", "mm" },
            { "%S", "ss" },
            { "%R", "HH:mm" },
            { "%T", "HH:mm:ss" },
            { "%r", "hh:mm:ss tt" },
            { "%n", "\n" },
            { "%t", "\t" },
            { "%%", "%" },
            { "%N", "fffffff" },
            { "%z", "zzz" },
            { "%Z", "TimeZoneNamePlaceholder" },
            { "%C", "CenturyPlaceholder" },
            { "%G", "IsoYearPlaceholder" },
            { "%V", "IsoWeekPlaceholder" },
        };

        private static readonly Dictionary<string, string> DotNetToChrono = new()
        {
            { "yyyy/MM/dd", "%F" },
            { "MM/dd/yyyy", "%D" },
            { "hh:mm:ss tt", "%r" },
            { "HH:mm:ss", "%T" },
            { "HH:mm", "%R" },
            { "yyyy", "%Y" },
            { "MMMM", "%B" },
            { "MMM", "%b" },
            { "MM", "%m" },
            { "dddd", "%A" },
            { "ddd", "%a" },
            { "dd", "%d" },
            { "d", "%e" },
            { "HH", "%H" },
            { "hh", "%I" },
            { "tt", "%p" },
            { "mm", "%M" },
            { "ss", "%S" },
            { "yy", "%y" },
            { "fffffff", "%N" },
            { "zzz", "%z" },
        };

        public static string GetTheRealFormat(string format)
        {
            if (string.IsNullOrEmpty(format))
                return format;

            var tokens = Regex.Matches(format, "%[A-Za-z%]");
            var result = format;
            var replacements = new Dictionary<string, string>();

            int tokenIndex = 0;
            foreach (System.Text.RegularExpressions.Match m in tokens)
            {
                string key = m.Value;
                string placeholder = $"\u0001{tokenIndex}\u0001";
                tokenIndex++;

                if (ChronoToDotNet.TryGetValue(key, out var dotNetFmt))
                {
                    if (dotNetFmt == "DayOfYearPlaceholder")
                        replacements[placeholder] = DateTime.Now.DayOfYear.ToString();
                    else if (dotNetFmt == "DayOfWeekNumPlaceholder")
                        replacements[placeholder] = ((int)DateTime.Now.DayOfWeek).ToString();
                    else if (dotNetFmt == "IsoDayOfWeekPlaceholder")
                    {
                        int d = (int)DateTime.Now.DayOfWeek;
                        replacements[placeholder] = (d == 0 ? 7 : d).ToString();
                    }
                    else if (dotNetFmt == "TimeZoneNamePlaceholder")
                        replacements[placeholder] = TimeZoneInfo.Local.DisplayName;
                    else if (dotNetFmt == "CenturyPlaceholder")
                        replacements[placeholder] = ((DateTime.Now.Year / 100) + 1).ToString();
                    else if (dotNetFmt == "IsoYearPlaceholder")
                        replacements[placeholder] = GetIsoYear(DateTime.Now).ToString();
                    else if (dotNetFmt == "IsoWeekPlaceholder")
                        replacements[placeholder] = GetIsoWeek(DateTime.Now).ToString("D2");
                    else
                        replacements[placeholder] = dotNetFmt;
                }
                else
                {
                    replacements[placeholder] = key;
                }

                int idx2 = result.IndexOf(key, StringComparison.Ordinal);
                if (idx2 >= 0)
                    result = result.Remove(idx2, key.Length).Insert(idx2, placeholder);
            }

            foreach (var (placeholder, value) in replacements)
            {
                result = result.Replace(placeholder, value);
            }

            return result;
        }

        public static string GetOriginalFormat(string format)
        {
            if (string.IsNullOrEmpty(format))
                return format;

            var result = format;
            var placeholders = new List<(string placeholder, string chronoFmt)>();

            var sortedKeys = new List<string>(DotNetToChrono.Keys);
            sortedKeys.Sort((a, b) => b.Length.CompareTo(a.Length));

            int idx = 0;
            foreach (var dotNetFmt in sortedKeys)
            {
                while (result.Contains(dotNetFmt))
                {
                    var placeholder = $"\u0001{idx}\u0001";
                    idx++;
                    int idx3 = result.IndexOf(dotNetFmt, StringComparison.Ordinal);
                    if (idx3 >= 0)
                        result = result.Remove(idx3, dotNetFmt.Length).Insert(idx3, placeholder);
                    placeholders.Add((placeholder, DotNetToChrono[dotNetFmt]));
                }
            }

            foreach (var (placeholder, chronoFmt) in placeholders)
            {
                result = result.Replace(placeholder, chronoFmt);
            }

            return result;
        }

        private static int GetIsoWeek(DateTime date)
        {
            return CultureInfo.InvariantCulture.Calendar.GetWeekOfYear(
                date, CalendarWeekRule.FirstFourDayWeek, DayOfWeek.Monday);
        }

        private static int GetIsoYear(DateTime date)
        {
            int week = GetIsoWeek(date);
            if (week == 1 && date.Month == 12)
                return date.Year + 1;
            if (week >= 52 && date.Month == 1)
                return date.Year - 1;
            return date.Year;
        }
    }
}
