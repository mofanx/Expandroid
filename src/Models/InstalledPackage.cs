using System;
using System.Collections.Generic;

namespace Expandroid.Models
{
    public class InstalledPackage
    {
        public string Name { get; set; }
        public string Version { get; set; }
        public string Author { get; set; }
        public string Description { get; set; }
        public DateTime InstalledDate { get; set; }
        public List<string> MatchFiles { get; set; } = new();
        public string Sha256 { get; set; }
    }

    public class HubPackageInfo
    {
        public string Name { get; set; }
        public string Title { get; set; }
        public string Description { get; set; }
        public string Author { get; set; }
        public string Version { get; set; }
        public string HomePage { get; set; }
        public string Tags { get; set; }
        public string ThumbnailUrl { get; set; }
        public string DownloadUrl { get; set; }
        public string Sha256 { get; set; }
    }

    public class HubPackageIndex
    {
        public List<HubPackageInfo> Packages { get; set; } = new();
        public DateTime LastUpdated { get; set; }
    }
}
