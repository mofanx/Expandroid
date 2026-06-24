using System.Text.Json.Serialization;
using YamlDotNet.Core;
using YamlDotNet.Serialization;

namespace Expandroid.Models
{
    public class DictWrapper
    {
        [JsonPropertyName("globalVars")]
        public List<Var> Global_vars { get; set; }
        [JsonPropertyName("matches")]
        public List<Match> Matches { get; set; }
        [YamlMember(Alias = "imports")]
        public List<string> Imports { get; set; }
    }
    public class FormOption
    {
        public bool Multiline { get; set; } = false;
        public string Type { get; set; } // list or choice
        public List<string> Values { get; set; }
    }
    public class Match
    {
        public Match()
        {

        }
        public Match(Match og)
        {
            Trigger = og.Trigger;
            Replace = og.Replace;
            Vars = new(og.Vars);
            Word = og.Word;
            Form = og.Form;
            Form_Fields = og.Form_Fields;
            Triggers = og.Triggers is not null ? new(og.Triggers) : null;
            LeftWord = og.LeftWord;
            RightWord = og.RightWord;
            PropagateCase = og.PropagateCase;
            UppercaseStyle = og.UppercaseStyle;
            Regex = og.Regex;
        }
        public string Trigger { get; set; }
        [YamlMember(Alias = "triggers")]
        public List<string> Triggers { get; set; }
        public string Replace { get; set; }
        public List<Var> Vars { get; set; }
        //[YamlMember(ScalarStyle = ScalarStyle.Literal)]
        public string Form { get; set; }
        public Dictionary<string, FormOption> Form_Fields { get; set; }
        public bool Word { get; set; } = false;
        [YamlMember(Alias = "left_word")]
        public bool LeftWord { get; set; } = false;
        [YamlMember(Alias = "right_word")]
        public bool RightWord { get; set; } = false;
        [YamlMember(Alias = "propagate_case")]
        public bool PropagateCase { get; set; } = false;
        [YamlMember(Alias = "uppercase_style")]
        public string UppercaseStyle { get; set; }
        [YamlMember(Alias = "regex")]
        public string Regex { get; set; }
    }
    public class Var
    {
        public Var() { }
        public Var(Var og)
        {
            Name = og.Name;
            Type = og.Type;
            Params = new(og.Params);
        }
        public string Name { get; set; }
        public string Type { get; set; } // echo, random, clipboard and date only supported
        public Params Params { get; set; }
    }
    public class Params
    {
        public Params() { }
        public Params(Params og)
        {
            Echo = og.Echo;
            Format = og.Format;
            Offset = og.Offset;
            Cmd = og.Cmd;
            Choices = og.Choices;
            Values = og.Values;
            Url = og.Url;
            Method = og.Method;
            Headers = og.Headers;
            Body = og.Body;
            JsonPath = og.JsonPath;
            Code = og.Code;
            Args = og.Args;
            Trim = og.Trim;
            IgnoreError = og.IgnoreError;
            MatchTrigger = og.MatchTrigger;
            IntentAction = og.IntentAction;
            ContentUri = og.ContentUri;
            ContentProjection = og.ContentProjection;
        }
        public string Echo { get; set; }
        public string Format { get; set; }
        public long Offset { get; set; } = 0;
        public string Cmd { get; set; }
        public string Layout { get; set; }
        public List<string> Choices { get; set; }
        [YamlMember(Alias = "values")]
        public List<string> Values { get; set; }
        [YamlMember(Alias = "url")]
        public string Url { get; set; }
        [YamlMember(Alias = "method")]
        public string Method { get; set; }
        [YamlMember(Alias = "headers")]
        public Dictionary<string, string> Headers { get; set; }
        [YamlMember(Alias = "body")]
        public string Body { get; set; }
        [YamlMember(Alias = "json_path")]
        public string JsonPath { get; set; }
        [YamlMember(Alias = "code")]
        public string Code { get; set; }
        [YamlMember(Alias = "args")]
        public List<string> Args { get; set; }
        [YamlMember(Alias = "trim")]
        public bool Trim { get; set; } = false;
        [YamlMember(Alias = "ignore_error")]
        public bool IgnoreError { get; set; } = false;
        [YamlMember(Alias = "trigger")]
        public string MatchTrigger { get; set; }
        [YamlMember(Alias = "action")]
        public string IntentAction { get; set; }
        [YamlMember(Alias = "uri")]
        public string ContentUri { get; set; }
        [YamlMember(Alias = "projection")]
        public List<string> ContentProjection { get; set; }
    }
}
