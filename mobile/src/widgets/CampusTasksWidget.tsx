import { Column, Row, Text } from '@expo/ui/jetpack-compose';
import { background, fillMaxWidth, paddingAll } from '@expo/ui/jetpack-compose/modifiers';
import { createWidget } from 'expo-widgets';

type CampusTasksProps = {
  count: number;
  title: string;
  due: string;
};

const CampusTasksWidget = (props: CampusTasksProps) => {
  'widget';
  return (
    <Column
      verticalArrangement={{ spacedBy: 7 }}
      modifiers={[fillMaxWidth(), background('#F4F0E5'), paddingAll(14)]}>
      <Text color="#315D42" style={{ fontSize: 10, fontWeight: 'bold', letterSpacing: 1 }}>
        CAMPUSOS · TASKS
      </Text>
      <Row verticalAlignment="center" horizontalArrangement={{ spacedBy: 8 }}>
        <Text color="#174C30" style={{ fontSize: 27, fontWeight: 'bold' }}>{props.count}</Text>
        <Text color="#315D42" style={{ fontSize: 13, fontWeight: 'bold' }}>
          {props.count === 1 ? 'pending task' : 'pending tasks'}
        </Text>
      </Row>
      <Text color="#1F2B22" style={{ fontSize: 14, fontWeight: 'bold' }} maxLines={1} overflow="ellipsis">
        {props.title}
      </Text>
      <Text color="#657467" style={{ fontSize: 11 }} maxLines={1} overflow="ellipsis">{props.due}</Text>
    </Column>
  );
};

export default createWidget('CampusTasksWidget', CampusTasksWidget, {
  count: 0,
  title: 'No pending tasks',
  due: 'Your task list is clear.'
});
