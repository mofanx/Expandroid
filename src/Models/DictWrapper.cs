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
        [YamlMember("imports")]
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
        [YamlMember("triggers")]
        public List<string> Triggers { get; set; }
        public string Replace { get; set; }
        public List<Var> Vars { get; set; }
        //[YamlMember(ScalarStyle = ScalarStyle.Literal)]
        public string Form { get; set; }
        public Dictionary<string, FormOption> Form_Fields { get; set; }
        public bool Word { get; set; } = false;
        [YamlMember("left_word")]
        public bool LeftWord { get; set; } = false;
        [YamlMember("right_word")]
        public bool RightWord { get; set; } = false;
        [YamlMember("propagate_case")]
        public bool PropagateCase { get; set; } = false;
        [YamlMember("uppercase_style")]
        public string UppercaseStyle { get; set; }
        [YamlMember("regex")]
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
        }
        public string Echo { get; set; }
        public string Format { get; set; }
        public long Offset { get; set; } = 0;
        public string Cmd { get; set; }
        public string Layout { get; set; }
        public List<string> Choices { get; set; }
        [YamlMember("values")]
        public List<string> Values { get; set; }
    }
}
