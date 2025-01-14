import {
  KeyboardAvoidingView,
  Text,
  UIManager,
  View,
  findNodeHandle,
} from 'react-native';
import React, { useEffect, useMemo, useRef, useState } from 'react';

import { ARModule } from '../../../../native/NativeModulesBridge';
import { DOWNLOAD_RADIUS } from '../../../constants/Constants';
import { ErrorModal } from '../../../components/modals/ErrorModal';
import { LoadingModal } from '../../../components/modals/LoadingModal';
import { LocationSearchBar } from '../../../components/input/LocationSearchBar';
import { NativeEventEmitter } from 'react-native';
import { NativeMapView } from '../../../../native/NativeViewsBridge';
import Orientation from 'react-native-orientation';
import { PlacesStackRouteNavProps } from '../../../navigation/params/RoutesParamList';
import { ScrollView } from 'react-native-gesture-handler';
import { TabScreen } from '../../../components/layout/TabScreen';
import { ThemeButton } from '../../../components/input/ThemeButton';
import { ThemeSlider } from '../../../components/input/ThemeSlider';
import { ThemeText } from '../../../components/text/ThemeText';
import { ThemeTextInput } from '../../../components/input/ThemeTextInput';
import useGeolocation from '../../../utils/hooks/useGeolocation';
import { useSettings } from '../../../utils/hooks/Hooks';
import useTheme from '../../../utils/hooks/useTheme';

interface LocationProps {
  longitude: number | null;
  latitude: number | null;
}

export function DownloadPlace({
  route,
}: PlacesStackRouteNavProps<'DownloadPlace'>) {
  const theme = useTheme();
  const { state } = useSettings();
  const location = useGeolocation();
  const [name, setName] = useState<string>('');
  const [description, setDescription] = useState<string>('');
  const [downloadModalVisible, setShowDownloadModalVisible] = useState<boolean>(
    false,
  );
  const [radius, setRadius] = useState<number>(15);
  const [radiusTimeout, setRadiusTimeout] = useState<
    NodeJS.Timeout | undefined
  >(undefined);
  const [locationMarker, setLocationMarker] = useState<LocationProps>({
    longitude: location.longitude,
    latitude: location.latitude,
  });

  const [errorModalVisible, setErrorModalVisible] = useState<boolean>(false);

  // const
  const mapRef = useRef<number | null>(null);
  const MapsManager = useMemo(
    () => UIManager.getViewManagerConfig('MapView'),
    [],
  );

  useEffect(() => {
    const eventEmitter = new NativeEventEmitter(ARModule);
    const downloadEventListener = eventEmitter.addListener(
      'DownloadEvent',
      (event) => {
        if (event.done && !event.error) {
          setShowDownloadModalVisible(false);
          setName('');
          setDescription('');
          setRadius('15');
        } else if (event.error) {
          setShowDownloadModalVisible(false);
          setErrorModalVisible(true);
        }
      },
    );

    return () => downloadEventListener.remove();
  }, []);

  useEffect(() => {
    const timeOutId = setTimeout(
      () =>
        UIManager.dispatchViewManagerCommand(
          mapRef.current,
          MapsManager.Commands.ZOOM_SET_BBOX.toString(),
          [locationMarker.latitude, locationMarker.longitude, radius, false], // map referece, use compass orientation, use observe location
        ),
      150,
    );
    return () => clearTimeout(timeOutId);
  }, [radius]);

  return (
    <>
      <TabScreen
        style={{
          flex: 1,
          display: 'flex',
          flexDirection: 'column',
        }}>
        <View style={{ flex: 0.38 }}>
          <ScrollView style={{ flex: 0.5 }}>
            <ThemeTextInput label="Name" value={name} onChangeText={setName} />
            <ThemeTextInput
              label="Description"
              value={description}
              onChangeText={setDescription}
            />
            <View style={{ marginTop: 4, padding: 8 }}>
              <View
                style={{
                  flexDirection: 'row',
                  justifyContent: 'space-between',
                  alignItems: 'center',
                }}>
                <ThemeText style={{ color: theme.colors.inactiveTint }}>
                  Radius Km
                </ThemeText>
                <ThemeText style={{ color: theme.colors.inactiveTint }}>
                  {`${radius} Km`}
                </ThemeText>
              </View>
              <ThemeSlider
                value={radius}
                step={1}
                range={DOWNLOAD_RADIUS}
                onValueChange={(value: number) => setRadius(value)}
              />
            </View>
          </ScrollView>
        </View>

        <View style={{ flex: 0.62 }}>
          <View style={{ marginBottom: 5 }}>
            <LocationSearchBar
              onItemSelected={(place) => {
                setLocationMarker({
                  latitude: place.lat,
                  longitude: place.lon,
                });
                UIManager.dispatchViewManagerCommand(
                  mapRef.current,
                  MapsManager.Commands.ZOOM_SET_BBOX.toString(),
                  [place.lat as number, place.lon as number, radius, true], // map referece, use compass orientation, use observe location
                );
              }}
            />
          </View>
          <View
            style={{
              flex: 1,
              borderColor: theme.colors.inactiveTint,
              borderWidth: 2,
              zIndex: -1,
            }}>
            <NativeMapView
              mapType={state.mapType}
              enableLocationTap={true}
              useObserverLocation={false}
              showBoundingCircle={true}
              enableZoom={true}
              style={{ width: '100%', height: '100%', flex: 1 }}
              onMapSingleTap={(event) => {
                const locationMarker = event.nativeEvent;
                setLocationMarker({
                  longitude: locationMarker.longitude,
                  latitude: locationMarker.latitude,
                });

                UIManager.dispatchViewManagerCommand(
                  mapRef.current,
                  MapsManager.Commands.ZOOM_SET_BBOX.toString(),
                  [
                    locationMarker.latitude,
                    locationMarker.longitude,
                    radius,
                    false,
                  ], // map referece, use compass orientation, use observe location
                );
              }}
              ref={(nativeRef) => (mapRef.current = findNodeHandle(nativeRef))}
            />
          </View>
          <ThemeButton
            disabled={
              !name ||
              !description ||
              locationMarker === null ||
              locationMarker.latitude === null ||
              locationMarker.longitude === null
            }
            icon="cloud-download"
            text="Download"
            onPress={async () => {
              setShowDownloadModalVisible(true);
              await ARModule.downloadAndStoreLocationData(
                name,
                description,
                locationMarker.latitude,
                locationMarker.longitude,
                radius,
              );
            }}
          />
        </View>
      </TabScreen>
      <LoadingModal
        isVisible={downloadModalVisible}
        text={'Downloading location data, this may take up to a minute.'}
      />
      <ErrorModal
        isVisible={errorModalVisible}
        text="Error occurred while trying to download data, please try again later"
        hide={() => setErrorModalVisible(false)}
      />
    </>
  );
}
