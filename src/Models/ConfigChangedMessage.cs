using CommunityToolkit.Mvvm.Messaging.Messages;

public class ConfigChangedMessage : ValueChangedMessage<bool>
{
    public ConfigChangedMessage() : base(true)
    {
    }
}
