import { Column, Text } from '@expo/ui/jetpack-compose';
import { background, fillMaxWidth, paddingAll } from '@expo/ui/jetpack-compose/modifiers';
import { createWidget } from 'expo-widgets';

type CampusNextClassProps = {
  title: string;
  time: string;
  detail: string;
};

const CampusNextClassWidget = (props: CampusNextClassProps) => {
  'widget';
  return (
    <Column
      verticalArrangement={{ spacedBy: 6 }}
      modifiers={[fillMaxWidth(), background('#174C30'), paddingAll(14)]}>
      <Text color="#CFE7D5" style={{ fontSize: 10, fontWeight: 'bold', letterSpacing: 1 }}>
        CAMPUSOS · NEXT CLASS
      </Text>
      <Text color="#FFFFFF" style={{ fontSize: 18, fontWeight: 'bold' }} maxLines={1} overflow="ellipsis">
        {props.title}
      </Text>
      <Text color="#F1F7F2" style={{ fontSize: 14, fontWeight: 'bold' }}>{props.time}</Text>
      <Text color="#CFE7D5" style={{ fontSize: 11 }} maxLines={2} overflow="ellipsis">
        {props.detail}
      </Text>
    </Column>
  );
};

export default createWidget('CampusNextClassWidget', CampusNextClassWidget, {
  title: 'Open CampusOS',
  time: 'Your next class appears here',
  detail: 'Add classes in Schedule to keep this widget updated.'
});
